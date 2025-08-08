import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import org.eclipse.jdt.core.dom.Statement;

/**
 * This class represents a refactoring in which integer variables whose values
 * represent the nullness of another variables are refactored into explicit null
 * checks
 */
public class SentinelRefactoring extends Refactoring {
	/**
	 * List of all sentinels found during traversal of the AST
	 */
	private final Dictionary<String, Sentinel> sentinels;

	/**
	 * Helper class for storing the values of a sentinel reference
	 */
	protected class Sentinel {
		private SimpleName sentinelName;
		private SimpleName VarName;
		private String nullValue;
		private String notNullValue;

		public Sentinel() {
		}

		public String getNullValue() {
			return this.nullValue;
		}

		public String getNotNullValue() {
			return this.notNullValue;
		}

		public void setNullCheck(String nullValue) {
			this.nullValue = nullValue;
		}

		public void setNotNullCheck(String notNullValue) {
			this.notNullValue = notNullValue;
		}

		public SimpleName getSentinelName() {
			return this.sentinelName;
		}

		public SimpleName setSentinelName(SimpleName sentinelName) {
			return this.sentinelName = sentinelName;
		}

		public SimpleName getVarName() {
			return this.VarName;
		}

		public SimpleName setVarName(SimpleName VarName) {
			return this.VarName = VarName;
		}

		public String toString() {
			return "Sentinel: \n\tnullValue: " + nullValue + "\n\tnotNullValue: " + notNullValue;
		}
	}

	public SentinelRefactoring() {
		super();
		this.sentinels = new Hashtable<>();
	}

	@Override
	public boolean isApplicable(ASTNode node) {
		if (!(node instanceof IfStatement ifStmt)) {
			return false;
		}
		List<Expression> exprs = Refactoring.parseExpression(ifStmt.getExpression());
		for (Expression expression : exprs) {

			if (!(expression instanceof InfixExpression infix)) {
				return false;
			}

			Expression leftOperand = infix.getLeftOperand();
			Expression rightOperand = infix.getRightOperand();
			InfixExpression.Operator operator = infix.getOperator();

			// Check if the condition does a check on an existing sentinel
			boolean isEqualityCheck = ((operator == InfixExpression.Operator.NOT_EQUALS
					|| operator == InfixExpression.Operator.EQUALS));
			boolean usesSentinel = ((leftOperand instanceof SimpleName lhs && sentinels.get(lhs.toString()) != null)
					|| (rightOperand instanceof SimpleName rhs && sentinels.get(rhs.toString()) != null));
			if (isEqualityCheck && usesSentinel) {
				return true;
			}

			// Check if condition uses a null check
			SimpleName varName = null;
			if ((leftOperand instanceof SimpleName vName && rightOperand instanceof NullLiteral)) {
				varName = vName;
			} else if ((rightOperand instanceof SimpleName vName && leftOperand instanceof NullLiteral)) {
				varName = vName;
			} else {
				return false;
			}

			if (!(ifStmt.getThenStatement() instanceof Block block)) {
				return false;
			}

			List<Statement> stmts = block.statements();

			// Checks that there is only one line in the ifStatement
			boolean isOneLine = stmts.size() == 1;
			if (!isOneLine)
				return false;

			// Checks that the single line is an assignment statement
			if (!(stmts.get(0) instanceof ExpressionStatement exprStmt
					&& exprStmt.getExpression() instanceof Assignment assign)) {
				return false;
			}

			leftOperand = assign.getLeftHandSide();
			rightOperand = assign.getRightHandSide();
			Assignment.Operator assignOperator = assign.getOperator();
			if (assignOperator != Assignment.Operator.ASSIGN) {
				return false;
			}

			SimpleName sentinelName = null;
			Expression nullValue = null;
			if (leftOperand instanceof SimpleName vName) {
				sentinelName = vName;
				nullValue = rightOperand;
			} else if (rightOperand instanceof SimpleName vName) {
				sentinelName = vName;
				nullValue = leftOperand;
			} else {
				return false;
			}

			String numLiteral;
			if (nullValue instanceof NumberLiteral numLit) {
				numLiteral = numLit.getToken();
			} else if (nullValue instanceof PrefixExpression pExpr) {
				numLiteral = pExpr.toString();
			} else {
				return false;
			}

			// At this point we have if (varName ?? null) sentinel = nullValue
			Sentinel sentinel = new Sentinel();
			sentinel.setVarName(varName);

			// if (varName != null) sentinelName = nullValue
			if (operator == InfixExpression.Operator.NOT_EQUALS) {
				sentinel.setSentinelName(sentinelName);
				sentinel.setNotNullCheck(numLiteral);
			}
			// if (varName == null) sentinelName = nullValue
			else if (operator == InfixExpression.Operator.EQUALS) {
				sentinel.setSentinelName(sentinelName);
				sentinel.setNullCheck(numLiteral);
			} else {
				return false;
			}
			sentinels.put(sentinelName.toString(), sentinel);
		}

		return false;
	}

	@Override
	public void apply(ASTNode node, ASTRewrite rewriter) {
		if (node instanceof IfStatement ifStmt) {
			List<Expression> exprs = Refactoring.parseExpression(ifStmt.getExpression());
			for (Expression expression : exprs) {
				if (!(expression instanceof InfixExpression infix)) {
					return;
				}

				Expression infixLeftOperand = infix.getLeftOperand();
				Expression infixRightOperand = infix.getRightOperand();
				InfixExpression.Operator infixOperator = infix.getOperator();

				boolean isEqualityCheck = (infix.getOperator() == InfixExpression.Operator.NOT_EQUALS
						|| infix.getOperator() == InfixExpression.Operator.EQUALS);

				if (!isEqualityCheck)
					return;

				SimpleName equalityVar;
				Expression equalityExpr;

				if (infixLeftOperand instanceof SimpleName vName) {
					equalityVar = vName;
					equalityExpr = infixRightOperand;
				} else if (infixRightOperand instanceof SimpleName vName) {
					equalityVar = vName;
					equalityExpr = infixLeftOperand;
				} else {
					return;
				}

				Expression replacement = getReplacementExpression(node, equalityVar, equalityExpr, infixOperator);
				if (replacement != null) {
					System.err.println("Replacing " + expression + " with " + replacement);
					rewriter.replace(expression, replacement, null);
				}

			}
		}
	}

	/**
	 * Parses an equality expression to find a check of a sentinel value
	 * 
	 * @param ast
	 *            The AST the Expression belongs to
	 * @param equalityVar
	 *            The name of the variable in the equality expression
	 * @param equalityExpr
	 *            The expression in the equality expression
	 * @param infixOperator
	 *            The operator in the equality expression
	 * @return The explicit null check the sentinel value represents, or null if not
	 *         sentinel check found
	 */
	public Expression getReplacementExpression(ASTNode node, SimpleName equalityVar, Expression equalityExpr,
			InfixExpression.Operator infixOperator) {
		Sentinel sentinel = sentinels.get(equalityVar.toString());
		if (sentinel == null)
			return null;
		AST ast = node.getAST();
		InfixExpression newCheck = ast.newInfixExpression();
		newCheck.setLeftOperand(ast.newSimpleName(sentinel.getVarName().toString()));
		newCheck.setRightOperand(ast.newNullLiteral());
		if (infixOperator == InfixExpression.Operator.EQUALS) {
			if (sentinel.getNullValue() != null) {
				if (equalityExpr.toString().equals(sentinel.getNullValue())) {
					newCheck.setOperator(InfixExpression.Operator.EQUALS);
				} else {
					newCheck.setOperator(InfixExpression.Operator.NOT_EQUALS);
				}
			} else if (sentinel.getNotNullValue() != null) {
				if (equalityExpr.toString().equals(sentinel.getNotNullValue())) {
					newCheck.setOperator(InfixExpression.Operator.NOT_EQUALS);
				} else {
					newCheck.setOperator(InfixExpression.Operator.EQUALS);
				}
			}
			return newCheck;
		} else if (infixOperator == InfixExpression.Operator.NOT_EQUALS) {
			if (sentinel.getNullValue() != null) {
				if (equalityExpr.toString().equals(sentinel.getNullValue())) {
					newCheck.setOperator(InfixExpression.Operator.NOT_EQUALS);
				} else {
					newCheck.setOperator(InfixExpression.Operator.EQUALS);
				}
			} else if (sentinel.getNotNullValue() != null) {
				if (equalityExpr.toString().equals(sentinel.getNotNullValue())) {
					newCheck.setOperator(InfixExpression.Operator.EQUALS);
				} else {
					newCheck.setOperator(InfixExpression.Operator.NOT_EQUALS);
				}

			}
			return newCheck;
		}
		return null;
	}
}
