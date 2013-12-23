package dotty.tools
package dotc
package typer

import core._
import ast._
import Trees._, Constants._, StdNames._, Scopes._, Denotations._
import Contexts._, Symbols._, Types._, SymDenotations._, Names._, NameOps._, Flags._, Decorators._
import ast.desugar, ast.desugar._
import Inferencing.{fullyDefinedType, AnySelectionProto, checkClassTypeWithStablePrefix}
import util.Positions._
import util.SourcePosition
import collection.mutable
import annotation.tailrec
import ErrorReporting._
import tpd.ListOfTreeDecorator
import language.implicitConversions

trait NamerContextOps { this: Context =>

  /** Enter symbol into current class, if current class is owner of current context,
   *  or into current scope, if not. Should always be called instead of scope.enter
   *  in order to make sure that updates to class members are reflected in
   *  finger prints.
   */
  def enter(sym: Symbol): Symbol = {
    ctx.owner match {
      case cls: ClassSymbol => cls.enter(sym)
      case _ => this.scope.asInstanceOf[MutableScope].enter(sym)
    }
    sym
  }

  /** The denotation with the given name in current context */
  def denotNamed(name: Name): Denotation =
    if (owner.isClass)
      if (outer.owner == owner)
        owner.thisType.member(name)
      else // we are in the outermost context belonging to a class; self is invisible here. See inClassContext.
        owner.findMember(name, owner.thisType, EmptyFlags)
    else
      scope.denotsNamed(name).toDenot(NoPrefix)

  /** Either the current scope, or, if the current context owner is a class,
   *  the declarations of the current class.
   */
  def effectiveScope: Scope =
    if (owner != null && owner.isClass) owner.asClass.decls
    else scope

  /** The symbol (stored in some typer's symTree) of an enclosing context definition */
  def symOfContextTree(tree: untpd.Tree) = {
    def go(ctx: Context): Symbol = {
      val typer = ctx.typer
      if (typer == null) NoSymbol
      else typer.symOfTree get tree match {
        case Some(sym) => sym
        case None =>
          var cx = ctx.outer
          while (cx.typer eq typer) cx = cx.outer
          go(cx)
      }
    }
    go(this)
  }
}

/** This class creates symbols from definitions and imports and gives them
 *  lazy types.
 *
 *  Timeline:
 *
 *  During enter, trees are expanded as necessary, populating the expandedTree map.
 *  Symbols are created, and the symOfTree map is set up.
 *
 *  Symbol completion causes some trees to be already typechecked and typedTree
 *  entries are created to associate the typed trees with the untyped expanded originals.
 *
 *  During typer, original trees are first expanded using expandedTree. For each
 *  expanded member definition or import we extract and remove the corresponding symbol
 *  from the symOfTree map and complete it. We then consult the typedTree map to see
 *  whether a typed tree exists already. If yes, the typed tree is returned as result.
 *  Otherwise, we proceed with regular type checking.
 *
 *  The scheme is designed to allow sharing of nodes, as long as each duplicate appears
 *  in a different method.
 */
class Namer { typer: Typer =>

  import untpd._

  /** A partial map from unexpanded member and pattern defs and to their expansions.
   *  Populated during enterSyms, emptied during typer.
   */
  lazy val expandedTree = new mutable.HashMap[DefTree, Tree] {
    override def default(tree: DefTree) = tree
  }

  /** A map from expanded MemberDef, PatDef or Import trees to their symbols.
   *  Populated during enterSyms, emptied at the point a typed tree
   *  with the same symbol is created (this can be when the symbol is completed
   *  or at the latest when the tree is typechecked.
   */
  lazy val symOfTree = new mutable.HashMap[Tree, Symbol]

  /** A map from expanded trees to their typed versions.
   *  Populated when trees are typechecked during completion (using method typedAhead).
   */
  lazy val typedTree = new mutable.HashMap[Tree, tpd.Tree]

  /** A map from method symbols to nested typers.
   *  Populated when methods are completed. Emptied when they are typechecked.
   *  The nested typer contains new versions of the four maps above including this
   *  one, so that trees that are shared between different DefDefs can be independently
   *  used as indices. It also contains a scope that contains nested parameters.
   */
  lazy val nestedTyper = new mutable.HashMap[Symbol, Typer]

  /** The scope of the typer.
   *  For nested typers this is a place parameters are entered during completion
   *  and where they survive until typechecking. A context with this typer also
   *  has this scope.
   */
  val scope = newScope

  /** The symbol of the given expanded tree. */
  def symbolOfTree(tree: Tree)(implicit ctx: Context): Symbol = {
    val xtree = expanded(tree)
    typedTree get xtree match {
      case Some(ttree) => ttree.denot.symbol
      case _ => symOfTree(xtree)
    }
  }

  /** The enclosing class with given name; error if none exists */
  def enclosingClassNamed(name: TypeName, pos: Position)(implicit ctx: Context): Symbol = {
    if (name.isEmpty) NoSymbol
    else {
      val cls = ctx.owner.enclosingClassNamed(name)
      if (!cls.exists) ctx.error(s"no enclosing class or object is named $name", pos)
      cls
    }
  }

  /** Find moduleClass/sourceModule in effective scope */
  private def findModuleBuddy(name: Name)(implicit ctx: Context) = {
    val scope = ctx.effectiveScope
    val it = scope.lookupAll(name).filter(_ is Module)
    assert(it.hasNext, s"no companion $name in $scope")
    it.next
  }

  /** If this tree is a member def or an import, create a symbol of it
   *  and store in symOfTree map.
   */
  def createSymbol(tree: Tree)(implicit ctx: Context): Symbol = {

    def privateWithinClass(mods: Modifiers) =
      enclosingClassNamed(mods.privateWithin, mods.pos)

    def record(sym: Symbol): Symbol = {
      symOfTree(tree) = sym
      sym
    }

    /** Add moduleClass/sourceModule to completer if it is for a module val or class */
    def adjustIfModule(completer: LazyType, tree: MemberDef) =
      if (tree.mods is Module) {
        val name = tree.name.encode
        if (name.isTermName)
          completer withModuleClass findModuleBuddy(name.moduleClassName)
        else
          completer withSourceModule findModuleBuddy(name.sourceModuleName)
      }
      else completer

    println(i"creating symbol for $tree")
    tree match {
      case tree: TypeDef if tree.isClassDef =>
        record(ctx.newClassSymbol(
          ctx.owner, tree.name.encode.asTypeName, tree.mods.flags,
          cls => adjustIfModule(new ClassCompleter(cls, tree)(ctx) withDecls newScope, tree),
          privateWithinClass(tree.mods), tree.pos, ctx.source.file))
      case tree: MemberDef =>
        val deferred = if (lacksDefinition(tree)) Deferred else EmptyFlags
        val method = if (tree.isInstanceOf[DefDef]) Method else EmptyFlags
        record(ctx.newSymbol(
          ctx.owner, tree.name.encode, tree.mods.flags | deferred | method,
          adjustIfModule(new Completer(tree), tree),
          privateWithinClass(tree.mods), tree.pos))
      case tree: Import =>
        record(ctx.newSymbol(
          ctx.owner, nme.IMPORT, Synthetic, new Completer(tree), NoSymbol, tree.pos))
      case _ =>
        NoSymbol
    }
  }

   /** If `sym` exists, enter it in effective scope. Check that
    *  package members are not entered twice in the same run.
    */
  def enterSymbol(sym: Symbol)(implicit ctx: Context) = {
    if (sym.exists) {
      println(s"entered: $sym in ${ctx.owner} and ${ctx.effectiveScope}")
      def preExisting = ctx.effectiveScope.lookup(sym.name)
      if (sym.owner is PackageClass) {
        if (preExisting.isDefinedInCurrentRun)
          ctx.error(s"${sym.showLocated} is compiled twice", sym.pos)
        }
      else if (!sym.owner.isClass && preExisting.exists) {
        ctx.error(i"${sym.name} is already defined as $preExisting")
      }
      ctx.enter(sym)
    }
    sym
  }

  /** Create package if it does not yet exist. */
  private def createPackageSymbol(pid: RefTree)(implicit ctx: Context): Symbol = {
    val pkgOwner = pid match {
      case Ident(_) => if (ctx.owner eq defn.EmptyPackageClass) defn.RootClass else ctx.owner
      case Select(qual: RefTree, _) => createPackageSymbol(qual).moduleClass
    }
    val existing = pkgOwner.info.decls.lookup(pid.name)
    if ((existing is Package) && (pkgOwner eq existing.owner)) existing
    else ctx.newCompletePackageSymbol(pkgOwner, pid.name.asTermName).entered
  }

  /** Expand tree and store in `expandedTree` */
  def expand(tree: Tree)(implicit ctx: Context): Unit = tree match {
    case mdef: DefTree =>
      val expanded = desugar.defTree(mdef)
      println(i"Expansion: $mdef expands to $expanded")
      if (expanded ne mdef) expandedTree(mdef) = expanded
    case _ =>
  }

  /** The expanded version of this tree, or tree itself if not expanded */
  def expanded(tree: Tree)(implicit ctx: Context): Tree = tree match {
    case ddef: DefTree => expandedTree(ddef)
    case _ => tree
  }

  /** A new context that summarizes an import statement */
  def importContext(sym: Symbol, selectors: List[Tree])(implicit ctx: Context) =
    ctx.fresh.withImportInfo(new ImportInfo(sym, selectors))

  /** A new context for the interior of a class */
  def inClassContext(selfInfo: DotClass /* Should be Type | Symbol*/)(implicit ctx: Context): Context = {
    val localCtx: Context = ctx.fresh.withNewScope
    selfInfo match {
      case sym: Symbol if sym.exists && sym.name != nme.WILDCARD =>
        localCtx.scope.asInstanceOf[MutableScope].enter(sym)
      case _ =>
    }
    localCtx
  }

  /** Expand tree and create top-level symbols for statement and enter them into symbol table */
  def index(stat: Tree)(implicit ctx: Context): Context = {
    expand(stat)
    indexExpanded(stat)
  }

  /** Create top-level symbols for all statements in the expansion of this statement and
   *  enter them into symbol table
   */
  def indexExpanded(stat: Tree)(implicit ctx: Context): Context = expanded(stat) match {
    case pcl: PackageDef =>
      val pkg = createPackageSymbol(pcl.pid)
      index(pcl.stats)(ctx.fresh.withOwner(pkg.moduleClass))
      ctx
    case imp: Import =>
      importContext(createSymbol(imp), imp.selectors)
    case mdef: DefTree =>
      enterSymbol(createSymbol(mdef))
      ctx
    case stats: Thicket =>
      for (tree <- stats.toList) enterSymbol(createSymbol(tree))
      ctx
    case _ =>
      ctx
  }

  /** Create top-level symbols for statements and enter them into symbol table */
  def index(stats: List[Tree])(implicit ctx: Context): Context = {

    /** Merge the definitions of a synthetic companion generated by a case class
     *  and the real companion, if both exist.
     */
    def mergeCompanionDefs() = {
      val caseClassDef = mutable.Map[TypeName, TypeDef]()
      for (cdef @ TypeDef(mods, name, _) <- stats)
        if (mods is Case) caseClassDef(name) = cdef
      for (mdef @ ModuleDef(_, name, _) <- stats)
        caseClassDef get name.toTypeName match {
          case Some(cdef) =>
            val Thicket(vdef :: (mcls @ TypeDef(_, _, impl: Template)) :: Nil) = expandedTree(mdef)
            val Thicket(cls :: mval :: TypeDef(_, _, compimpl: Template) :: crest) = expandedTree(cdef)
            val mcls1 = cpy.TypeDef(mcls, mcls.mods, mcls.name,
              cpy.Template(impl, impl.constr, impl.parents, impl.self,
                compimpl.body ++ impl.body))
            expandedTree(mdef) = Thicket(vdef :: mcls1 :: Nil)
            expandedTree(cdef) = Thicket(cls :: crest)
          case none =>
        }
    }

    stats foreach expand
    mergeCompanionDefs()
    (ctx /: stats) ((ctx, stat) => indexExpanded(stat)(ctx))
  }

  /** The completer of a symbol defined by a member def or import (except ClassSymbols) */
  class Completer(original: Tree)(implicit ctx: Context) extends LazyType {

    protected def localContext(owner: Symbol) = ctx.fresh.withOwner(owner).withTree(original)

    private def typeSig(sym: Symbol): Type = original match {
      case original: ValDef =>
        valOrDefDefSig(original, sym, identity)(localContext(sym).withNewScope)
      case original: DefDef =>
        val typer1 = new Typer
        nestedTyper(sym) = typer1
        typer1.defDefSig(original, sym)(localContext(sym).withTyper(typer1))
      case original: TypeDef =>
        assert(!original.isClassDef)
        typeDefSig(original, sym)(localContext(sym).withNewScope)
      case imp: Import =>
        val expr1 = typedAheadExpr(imp.expr, AnySelectionProto)
        ImportType(tpd.SharedTree(expr1))
    }

    def complete(denot: SymDenotation): Unit =
      denot.info = typeSig(denot.symbol)
  }

  class ClassCompleter(cls: ClassSymbol, original: TypeDef)(ictx: Context) extends Completer(original)(ictx) {

    protected implicit val ctx = localContext(cls)

    /** The type signature of a ClassDef with given symbol */
    override def complete(denot: SymDenotation): Unit = {

      /** The type of a parent constructor. Types constructor arguments
       *  only if parent type contains uninstantiated type parameters.
       */
      def parentType(constr: untpd.Tree): Type = {
        val (core, targs) = stripApply(constr) match {
          case TypeApply(core, targs) => (core, targs)
          case core => (core, Nil)
        }
        val Select(New(tpt), nme.CONSTRUCTOR) = core
        val targs1 = targs map (typedAheadType(_))
        val ptype = typedAheadType(tpt).tpe appliedTo targs1.tpes
        if (ptype.uninstantiatedTypeParams.isEmpty) ptype
        else typedAheadExpr(constr).tpe
      }

      val TypeDef(_, name, impl @ Template(constr, parents, self, body)) = original

      val (params, rest) = body span {
        case td: TypeDef => td.mods is Param
        case td: ValDef => td.mods is ParamAccessor
        case _ => false
      }
      index(params)
      val selfInfo =
        if (self.isEmpty) NoType
        else if (cls is Module) cls.owner.thisType select sourceModule
        else createSymbol(self)
      // pre-set info, so that parent types can refer to type params
      denot.info = ClassInfo(cls.owner.thisType, cls, Nil, decls, selfInfo)
      val parentTypes = parents map parentType
      val parentRefs = ctx.normalizeToClassRefs(parentTypes, cls, decls)
      val parentClsRefs =
        for ((parentRef, constr) <- parentRefs zip parents)
          yield checkClassTypeWithStablePrefix(parentRef, constr.pos)

      index(constr)
      index(rest)(inClassContext(selfInfo))
      denot.info = ClassInfo(cls.owner.thisType, cls, parentClsRefs, decls, selfInfo)
    }
  }

  /** Typecheck tree during completion, and remember result in typedtree map */
  private def typedAheadImpl(tree: Tree, pt: Type)(implicit ctx: Context): tpd.Tree =
    typedTree.getOrElseUpdate(expanded(tree), typer.typedUnadapted(tree, pt))

  def typedAheadType(tree: Tree, pt: Type = WildcardType)(implicit ctx: Context): tpd.Tree =
    typedAheadImpl(tree, pt)(ctx retractMode Mode.PatternOrType addMode Mode.Type)

  def typedAheadExpr(tree: Tree, pt: Type = WildcardType)(implicit ctx: Context): tpd.Tree =
    typedAheadImpl(tree, pt)(ctx retractMode Mode.PatternOrType)

  /** Enter and typecheck parameter list */
  def completeParams(params: List[MemberDef])(implicit ctx: Context) = {
    index(params)
    for (param <- params) typedAheadExpr(param)
  }

  /** The type signature of a ValDef or DefDef
   *  @param mdef     The definition
   *  @param sym      Its symbol
   *  @param paramFn  A wrapping function that produces the type of the
   *                  defined symbol, given its final return type
   */
  def valOrDefDefSig(mdef: ValOrDefDef, sym: Symbol, paramFn: Type => Type)(implicit ctx: Context): Type = {
    val pt =
      if (!mdef.tpt.isEmpty) WildcardType
      else {
        val inherited =
          if ((sym is Param) && sym.owner.isSetter) { // fill in type from getter result type
            val getterCtx = ctx.outersIterator
                .dropWhile(_.owner != sym.owner)
                .dropWhile(_.owner == sym.owner)
                .next
            getterCtx.denotNamed(sym.owner.asTerm.name.setterToGetter).info.widenExpr
          }
          else if (sym.owner.isTerm) NoType
          else {
            // TODO: Look only at member of supertype instead?
            lazy val schema = paramFn(WildcardType)
            val site = sym.owner.thisType
            ((NoType: Type) /: sym.owner.info.baseClasses.tail) { (tp, cls) =>
              val itpe = cls.info
                .nonPrivateDecl(sym.name)
                .matchingDenotation(site, schema)
                .info.finalResultType
                .asSeenFrom(site, cls)
              tp & itpe
            }
          }
        // println(s"final inherited for $sym: ${inherited.toString}") !!!
        // println(s"owner = ${sym.owner}, decls = ${sym.owner.info.decls.show}")
        val rhsCtx = ctx.fresh addMode Mode.InferringReturnType
        def rhsType = adapt(typedAheadExpr(mdef.rhs)(rhsCtx), WildcardType).tpe.widen
        def lhsType = fullyDefinedType(rhsType, "right-hand side", mdef.pos)
        inherited orElse lhsType
      }
    paramFn(typedAheadType(mdef.tpt, pt).tpe)
  }

  /** The type signature of a DefDef with given symbol */
  def defDefSig(ddef: DefDef, sym: Symbol)(implicit ctx: Context) = {
    val DefDef(_, name, tparams, vparamss, _, _) = ddef
    completeParams(tparams)
    vparamss foreach completeParams
    val isConstructor = name == nme.CONSTRUCTOR
    val isSecondaryConstructor = isConstructor && sym != sym.owner.primaryConstructor
    def typeParams =
      if (isSecondaryConstructor) sym.owner.primaryConstructor.typeParams
      else tparams map symbolOfTree
    def wrapMethType(restpe: Type): Type = {
      val monotpe =
        (vparamss :\ restpe) { (params, restpe) =>
          val make =
            if (params.nonEmpty && (params.head.mods is Implicit)) ImplicitMethodType
            else MethodType
          make.fromSymbols(params map symbolOfTree, restpe)
        }
      if (typeParams.nonEmpty) PolyType.fromSymbols(typeParams, monotpe)
      else if (vparamss.isEmpty) ExprType(monotpe)
      else monotpe
    }
    if (isConstructor) {
      // set result type tree to unit, but take the current class as result type of the symbol
      typedAheadType(ddef.tpt, defn.UnitType)
      wrapMethType(sym.owner.typeRef.appliedTo(typeParams map (_.typeRef)))
    }
    else valOrDefDefSig(ddef, sym, wrapMethType)
  }

  def typeDefSig(tdef: TypeDef, sym: Symbol)(implicit ctx: Context): Type = {
    completeParams(tdef.tparams)
    val tparamSyms = tdef.tparams map symbolOfTree
    val rhsType = typedAheadType(tdef.rhs).tpe

    rhsType match {
      case bounds: TypeBounds =>
        if (tparamSyms.nonEmpty) bounds.higherKinded(tparamSyms)
        else rhsType
      case _ =>
        if (tparamSyms.nonEmpty) rhsType.LambdaAbstract(tparamSyms)(ctx.error(_, _))
        else TypeAlias(rhsType, if (sym is Local) sym.variance else 0)
    }
  }
}