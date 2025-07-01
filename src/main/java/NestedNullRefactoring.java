import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

/**
 * This class represents a refactoring in which calls to one-line methods which
 * test for nullness of a variable are replaced with an explicit check
 */
public class NestedNullRefactoring extends Refactoring {
	/**
	 * List of variable names idnetified as boolean flags, along with their
	 * corresponding initializer expression
	 */
	private final Dictionary<String, Expression> applicableMethods;

	/** Default constructor */
	public NestedNullRefactoring() {
		applicableMethods = new Hashtable<>();
	}

	/*
	 * Returns true is Node is a one-line method that returns the result of a null
	 * check
	 */
	@Override
	public boolean isApplicable(ASTNode node) {

		// Check if Method Invocation is in applicableMethods
		if (node instanceof PrefixExpression prefix) {
			if (prefix.getOperand() instanceof MethodInvocation invocation
					&& applicableMethods.get(invocation.toString()) != null) {
				System.out.println("[DEBUG] Invocation of appliccable method found");
				return true;
			}
			return false;
		}
		if (node instanceof MethodInvocation invocation) {
			if (applicableMethods.get(invocation.toString()) != null) {
				System.out.println("[DEBUG] Invocation of appliccable method found");
				return true;
			}
			return false;
		}

		// Confirm that the ASTNode is a method
		if (!(node instanceof MethodDeclaration)) {
			return false;
		}

		// Confirm that the method returns a boolean
		MethodDeclaration method = (MethodDeclaration) node;
		Type retType = method.getReturnType2();
		if (retType == null)
			return false;
		boolean isBooleanDeclaration = (retType.isPrimitiveType()
				&& ((PrimitiveType) retType).getPrimitiveTypeCode() == PrimitiveType.BOOLEAN);
		if (!(isBooleanDeclaration))
			return false;

		// Checks if there are any parameters
		// TODO: Make work with Parameters
		boolean hasParams = method.parameters().size() > 0;
		if (hasParams)
			return false;

		Block body = method.getBody();
		if (body == null)
			return false;
		List<Statement> stmts = body.statements();

		// Checks if there is only one line
		boolean isOneLine = stmts.size() == 1;
		if (!isOneLine)
			return false;

		// Possible unneccessary since we already confirmed return type is not void
		Statement stmt = stmts.get(0);
		if (!(stmt instanceof ReturnStatement)) {
			return false;
		}

		// Checks to make sure return statement is of a single equality check
		Expression retExpr = ((ReturnStatement) stmt).getExpression();
		if (!(retExpr instanceof InfixExpression)) {
			return false;
		}

		InfixExpression infix = (InfixExpression) retExpr;

		if ((infix.getOperator() == InfixExpression.Operator.NOT_EQUALS
				|| infix.getOperator() == InfixExpression.Operator.EQUALS)) {
			Expression leftOperand = infix.getLeftOperand();
			Expression rightOperand = infix.getRightOperand();

			if ((leftOperand instanceof SimpleName && rightOperand instanceof NullLiteral)
					|| (rightOperand instanceof SimpleName && leftOperand instanceof NullLiteral)) {
				System.out.println("[DEBUG] Found one line null check method: " + method.getName());
				applicableMethods.put(method.getName().toString() + "()", retExpr);
			}
		}
		return false;
	}

	@Override
	public void apply(ASTNode node, ASTRewrite rewriter) {
		// Check if Method Invocation is in applicableMethods
		if (node instanceof MethodInvocation invocation) {
			String invocationName = invocation.toString();
			Expression expr = (applicableMethods.get(invocationName));
			System.out.println("Replacing \n" + invocation + "\nWith \n" + expr);
			rewriter.replace(invocation, expr, null);
		} else if (node instanceof PrefixExpression prefix
				&& prefix.getOperator() == PrefixExpression.Operator.NOT
				&& prefix.getOperand() instanceof MethodInvocation invocation) {
			String invocationName = invocation.toString();
			Expression expr = (applicableMethods.get(invocationName));
			AST ast = node.getAST();
			InfixExpression infix = (InfixExpression) expr;
			InfixExpression newInfix = ast.newInfixExpression();
			if (infix.getLeftOperand() instanceof SimpleName name) {
				newInfix.setLeftOperand(ast.newSimpleName(name.toString()));
				newInfix.setRightOperand(ast.newNullLiteral());
			} else if (infix.getRightOperand() instanceof SimpleName name) {
				newInfix.setLeftOperand(ast.newNullLiteral());
				newInfix.setRightOperand(ast.newSimpleName(name.toString()));
			}
			InfixExpression.Operator originalOperator = infix.getOperator();
			if (originalOperator == InfixExpression.Operator.EQUALS) {
				newInfix.setOperator(InfixExpression.Operator.NOT_EQUALS);
			} else if (originalOperator == InfixExpression.Operator.NOT_EQUALS) {
				newInfix.setOperator(InfixExpression.Operator.EQUALS);
			}
			System.out.println("Replacing \n" + node + "\nWith \n" + infix);
			rewriter.replace(node, newInfix, null);

		}
	}
}
