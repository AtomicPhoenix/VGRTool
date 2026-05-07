import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A refactoring module that replaces checks on variables whose nullness is
 * dependent on the nullness of another with another variable, by checking the
 * original (independent) variable directly.
 * <p>
 * Example:
 * 
 * <pre>{@code
 * // Before:
 * Class<?> dependentVar = (independentVar != null ? independentVar.getDependent() : null);
 * if (dependentVar != null) {
 * 	// ...
 * }
 *
 * // After:
 * Class<?> dependentVar = (independentVar != null ? independentVar.getDependent() : null);
 * if (independentVar != null) {
 * 	// ...
 * }
 * }</pre>
 * <p>
 */
public class AddNullCheckBeforeDereferenceRefactoring extends Refactoring {
	public static final String NAME = "AddNullCheckBeforeDereferenceRefactoring";
	private static final Logger LOGGER = LogManager.getLogger();

	/**
	 * List of dependent variables and the independent variable they rely on * Uses
	 * each variable's ({@link org.eclipse.jdt.core.dom.IVariableBinding}) as the
	 * key, ensuring global uniqueness. Two variables who have the same name but
	 * have different scopes will have different IBinding instances.
	 */
	private final Map<IBinding, Expression> validRefactors;

	/** Default constructor (for RefactoringEngine integration) */
	public AddNullCheckBeforeDereferenceRefactoring() {
		super();
		validRefactors = new HashMap<>();
	}

	@Override
	public boolean isApplicable(ASTNode node) {
		if (node instanceof VariableDeclarationFragment varFrag) {
			return isApplicable(varFrag);
		}

		if (node instanceof IfStatement ifStmt) {
			return isApplicable(ifStmt);
		}

		if (node instanceof Assignment assignment) {
			verifyRefactors(assignment);
		}

		if ((node instanceof MethodInvocation || node instanceof SuperMethodInvocation) && !VGRTool.unsound) {
			LOGGER.debug("Clearing all stored refactors due to method invocation...");
			validRefactors.clear();
		}

		LOGGER.debug("Node " + node.getClass().getSimpleName() + " is NOT applicable. Skipping.");
		return false;
	}

	private boolean isApplicable(VariableDeclarationFragment var) {
		Expression initializer = var.getInitializer();
		if (initializer == null)
			return false;
		List<Expression> varInitializerFragments = getSubExpressions(initializer);
		AST ast = var.getAST();
		for (Expression varInitFrag : varInitializerFragments) {

			Expression condition;
			if (varInitFrag instanceof ConditionalExpression ternary) {
				if (ternary.getThenExpression() instanceof NullLiteral) {
					// depObj != null when condition is false
					ParenthesizedExpression tempParen = ast.newParenthesizedExpression();
					tempParen.setExpression((Expression) ASTNode.copySubtree(ast, ternary.getExpression()));;

					PrefixExpression tempPrefix = ast.newPrefixExpression();
					tempPrefix.setOperator(PrefixExpression.Operator.NOT);
					tempPrefix.setOperand(tempParen);
					condition = tempPrefix;
				} else if (ternary.getElseExpression() instanceof NullLiteral) {
					// depObj != null when condition is true
					condition = ternary.getExpression();
				} else {
					// Ternary must contain NullLiteral
					continue;
				}
				LOGGER.debug("Found Ternary Assignment: %s", var.getName());
				LOGGER.debug("Found Ternary Condition: %s", condition);
				validRefactors.put(var.resolveBinding(), condition);
			}
		}
		return false;
	}

	private boolean isApplicable(IfStatement ifStmt) {
		Expression ifStmtCondition = ifStmt.getExpression();
		LOGGER.debug("Analyzing if-statement: %s", ifStmtCondition);
		List<Expression> conditionFragments = Refactoring.getSubExpressions(ifStmtCondition);
		for (Expression condition : conditionFragments) {
			if (!(condition instanceof InfixExpression infix)) {
				continue;
			}

			if (infix.getOperator() != InfixExpression.Operator.NOT_EQUALS) {
				continue;
			}

			Expression leftOperand = infix.getLeftOperand();
			Expression rightOperand = infix.getRightOperand();

			SimpleName varName;
			if (rightOperand instanceof SimpleName rightVarName && leftOperand instanceof NullLiteral) {
				varName = rightVarName;
			} else if (leftOperand instanceof SimpleName leftVarName && rightOperand instanceof NullLiteral) {
				varName = leftVarName;
			} else {
				continue;
			}
			if (validRefactors.get(varName.resolveBinding()) != null) {
				LOGGER.debug("Found indirect null check in if-statement: " + condition);
				return true;
			}
		}
		LOGGER.debug("No valid refactors found for IfStatement %s", ifStmt);
		return false;
	}

	@Override
	public void apply(ASTNode node, ASTRewrite rewriter) {
		if (!(node instanceof IfStatement ifStmt)) {
			return;
		}

		Expression ifStmtCondition = ifStmt.getExpression();
		LOGGER.debug("Analyzing if-statement: " + ifStmtCondition);
		List<Expression> conditionFragments = Refactoring.getSubExpressions(ifStmtCondition);

		for (Expression condition : conditionFragments) {
			// Skip non-equality check conditionals
			if (!(condition instanceof InfixExpression infix)) {
				continue;
			}

			Expression leftOperand = infix.getLeftOperand();
			Expression rightOperand = infix.getRightOperand();

			SimpleName varName;
			if (rightOperand instanceof SimpleName rightVarName && leftOperand instanceof NullLiteral) {
				varName = rightVarName;
			} else if (leftOperand instanceof SimpleName leftVarName && rightOperand instanceof NullLiteral) {
				varName = leftVarName;
			} else {
				continue;
			}

			Expression ternary = validRefactors.get(varName.resolveBinding());
			if (ternary == null) {
				continue;
			}

			AST ast = node.getAST();
			ParenthesizedExpression pExpression = ast.newParenthesizedExpression();
			Expression expr = (Expression) ASTNode.copySubtree(ast, ternary);
			if (expr == null) {
				continue;
			}
			pExpression.setExpression(expr);

			LOGGER.debug("[DEBUG] Replacing Variable: " + varName);
			LOGGER.debug("[DEBUG] New Value: " + pExpression);

			rewriter.replace(condition, pExpression, null);
		}

	}

	/*
	 * Checks Assignment node to see if it re-assigns an existing valid refactoring,
	 * and if so removes it from validRefactors
	 */
	private void verifyRefactors(Assignment assignmentNode) {
		Expression lhs = assignmentNode.getLeftHandSide();
		if (!(lhs instanceof SimpleName varName)) {
			return;
		}
		if (validRefactors.get(varName.resolveBinding()) != null) {
			validRefactors.remove(varName.resolveBinding());
		}
	}
}
