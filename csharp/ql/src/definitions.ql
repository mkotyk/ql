/**
 * @name Jump-to-definition links
 * @description Generates use-definition pairs that provide the data
 *              for jump-to-definition in the code viewer.
 * @kind definitions
 * @id cs/jump-to-definition
 */

import csharp

/** An element with an associated definition. */
abstract class Use extends @type_mention_parent {
  /**
   * Holds if this element is at the specified location.
   * The location spans column `startcolumn` of line `startline` to
   * column `endcolumn` of line `endline` in file `filepath`.
   * For more information, see
   * [LGTM locations](https://lgtm.com/help/ql/locations).
   */
  predicate hasLocationInfo(
    string filepath, int startline, int startcolumn, int endline, int endcolumn
  ) {
    exists(Location l |
      l = this.(Element).getLocation() or
      l = this.(TypeMention).getLocation()
    |
      filepath = l.getFile().getAbsolutePath() and
      startline = l.getStartLine() and
      startcolumn = l.getStartColumn() and
      endline = l.getEndLine() and
      endcolumn = l.getEndColumn()
    )
  }

  /** Gets the definition associated with this element. */
  abstract Declaration getDefinition();

  /**
   * Gets the type of use.
   *
   * - `"M"`: call.
   * - `"V"`: variable use.
   * - `"T"`: type reference.
   */
  abstract string getUseType();

  /** Gets a textual representation of this element. */
  abstract string toString();
}

/** A method call/access. */
class MethodUse extends Use, QualifiableExpr {
  MethodUse() {
    this instanceof MethodCall or
    this instanceof MethodAccess
  }

  /** Gets the qualifier of this method use, if any. */
  private Expr getFormatQualifier() {
    (
      if this.getQualifiedDeclaration().(Method).isExtensionMethod()
      then result = this.(MethodCall).getArgument(0)
      else result = this.getQualifier()
    ) and
    not result.isImplicit()
  }

  override predicate hasLocationInfo(
    string filepath, int startline, int startcolumn, int endline, int endcolumn
  ) {
    Use.super.hasLocationInfo(filepath, _, _, _, _) and
    endline = startline and
    endcolumn = startcolumn + this.getQualifiedDeclaration().getName().length() - 1 and
    (
      exists(Location ql | ql = this.getFormatQualifier().getLocation() |
        startline = ql.getEndLine() and
        startcolumn = ql.getEndColumn() + 2
      )
      or
      not exists(this.getFormatQualifier()) and
      exists(Location l | l = this.getLocation() |
        startline = l.getStartLine() and
        startcolumn = l.getStartColumn()
      )
    )
  }

  override Method getDefinition() { result = getQualifiedDeclaration().getSourceDeclaration() }

  override string getUseType() { result = "M" }

  override string toString() { result = this.(Expr).toString() }
}

/** An access. */
class AccessUse extends Access, Use {
  AccessUse() {
    not this.getTarget().(Parameter).getCallable() instanceof Accessor and
    not this = any(LocalVariableDeclAndInitExpr d).getLValue() and
    not this.isImplicit() and
    not this instanceof MethodAccess and // handled by `MethodUse`
    not this instanceof TypeAccess and // handled by `TypeMentionUse`
    not this.(FieldAccess).getParent() instanceof Field // Enum initializer
  }

  /** Gets the qualifier of this acccess, if any. */
  private Expr getFormatQualifier() {
    result = this.(QualifiableExpr).getQualifier() and
    not result.isImplicit()
  }

  override predicate hasLocationInfo(
    string filepath, int startline, int startcolumn, int endline, int endcolumn
  ) {
    exists(Location ql | ql = this.getFormatQualifier().getLocation() |
      startline = ql.getEndLine() and
      startcolumn = ql.getEndColumn() + 2 and
      Use.super.hasLocationInfo(filepath, _, _, endline, endcolumn)
    )
    or
    not exists(this.getFormatQualifier()) and
    Use.super.hasLocationInfo(filepath, startline, startcolumn, endline, endcolumn)
  }

  override Declaration getDefinition() { result = this.getTarget().getSourceDeclaration() }

  override string getUseType() {
    if this instanceof Call or this instanceof LocalFunctionAccess
    then result = "M"
    else
      if this instanceof BaseAccess or this instanceof ThisAccess
      then result = "T"
      else result = "V"
  }

  override string toString() { result = this.(Access).toString() }
}

/** A type mention. */
class TypeMentionUse extends Use, TypeMention {
  TypeMentionUse() {
    // In type mentions such as `T[]`, `T?`, `T*`, and `(S, T)`, we only want
    // uses for the nested type mentions
    forall(TypeMention child, Type t |
      child.getParent() = this and
      t = this.getType()
    |
      not t instanceof ArrayType and
      not t instanceof NullableType and
      not t instanceof PointerType and
      not t instanceof TupleType
    )
  }

  override Type getDefinition() { result = this.getType().getSourceDeclaration() }

  override string getUseType() {
    if this.getTarget() instanceof ObjectCreation
    then result = "M" // constructor call
    else result = "T"
  }

  override string toString() { result = TypeMention.super.toString() }
}

from Use use, Declaration definition
where
  definition = use.getDefinition() and
  definition.fromSource()
select use, definition, use.getUseType()
