package com.semmle.js.ast;

import java.util.Collections;
import java.util.List;

import com.semmle.ts.ast.DecoratorList;
import com.semmle.ts.ast.ITypeExpression;
import com.semmle.ts.ast.TypeParameter;

/**
 * A function declaration such as
 *
 * <pre>
 * function add(x, y) {
 *   return x+y;
 * }
 * </pre>
 */
public class FunctionDeclaration extends Statement implements IFunction {
	private final AFunction<BlockStatement> fn;
	private final boolean hasDeclareKeyword;
	private int symbol = -1;

	public FunctionDeclaration(SourceLocation loc, Identifier id, List<Expression> params, BlockStatement body, boolean generator,
			boolean async) {
		this(loc, new AFunction<BlockStatement>(id, params, body, generator, async, Collections.emptyList(), Collections.emptyList(),
				Collections.emptyList(), null, null),
				false);
	}

	public FunctionDeclaration(SourceLocation loc, Identifier id,
			List<Expression> params, BlockStatement body, boolean generator, boolean async, boolean hasDeclareKeyword,
			List<TypeParameter> typeParameters, List<ITypeExpression> parameterTypes, ITypeExpression returnType,
			ITypeExpression thisParameterType) {
		this(loc, new AFunction<BlockStatement>(id, params, body, generator, async, typeParameters, parameterTypes, Collections.emptyList(),
				returnType, thisParameterType), hasDeclareKeyword);
	}

	private FunctionDeclaration(SourceLocation loc, AFunction<BlockStatement> fn, boolean hasDeclareKeyword) {
		super("FunctionDeclaration", loc);
		this.fn = fn;
		this.hasDeclareKeyword = hasDeclareKeyword;
	}

	@Override
	public <Q, A> A accept(Visitor<Q, A> v, Q q) {
		return v.visit(this, q);
	}

	public FunctionExpression asFunctionExpression() {
		return new FunctionExpression(getLoc(), fn);
	}

	@Override public Identifier getId()            { return fn.getId();        }
	@Override public List<IPattern> getParams()    { return fn.getParams();    }
	@Override public boolean hasDefault(int i)     { return fn.hasDefault(i);  }
	@Override public Expression getDefault(int i)  { return fn.getDefault(i);  }
	@Override public IPattern getRest()            { return fn.getRest();      }
	@Override public BlockStatement getBody()      { return fn.getBody();      }
	@Override public boolean hasRest()             { return fn.hasRest();      }
	public boolean hasId()                         { return fn.hasId();        }
	public boolean isGenerator()                   { return fn.isGenerator();  }
	public boolean isAsync()                       { return fn.isAsync();      }
	public List<IPattern> getAllParams()           { return fn.getAllParams(); }
	@Override public List<Expression> getRawParameters() { return fn.getRawParams(); }
	public ITypeExpression getReturnType()         { return fn.getReturnType(); }
	@Override public boolean hasParameterType(int i) { return fn.hasParameterType(i); }
	@Override public ITypeExpression getParameterType(int i) { return fn.getParameterType(i); }
	public List<ITypeExpression> getParameterTypes() { return fn.getParameterTypes(); }
	public List<TypeParameter> getTypeParameters() { return fn.getTypeParameters(); }
	public ITypeExpression getThisParameterType() { return fn.getThisParameterType(); }
	public List<DecoratorList> getParameterDecorators() { return fn.getParameterDecorators(); }

	public boolean hasDeclareKeyword() {
		return hasDeclareKeyword;
	}

	@Override
	public int getSymbol() {
		return this.symbol;
	}

	@Override
	public void setSymbol(int symbol) {
		this.symbol = symbol;
	}
}
