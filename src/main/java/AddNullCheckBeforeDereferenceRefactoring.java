import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

/**
 * A refactoring module that replaces checks on variables whose nullness is
 * dependent on the nullness of another with another variable, by checking the
 * original (independent) variable directly.*
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

	/**
	 * List of depdendent variables and the independent variable they rely on
	 */
	private final Dictionary<String, ConditionalExpression> validRefactors;

	/**
	 * Default constructor (for RefactoringEngine integration)
	 */
	public AddNullCheckBeforeDereferenceRefactoring() {
		super();
		validRefactors = new Hashtable<>();
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

		// System.out.println("[DEBUG] Node " + node.getClass().getSimpleName() + " is
		// NOT applicable. Skipping.");
		return false;
	}

	private boolean isApplicable(VariableDeclarationFragment var) {
		List<Expression> varInitializerFragments = getSubExpressions(var.getInitializer());

		for (Expression varInitFrag : varInitializerFragments) {
			if ((varInitFrag instanceof ConditionalExpression ternary)
					&& ((ternary.getThenExpression() instanceof NullLiteral)
							|| ternary.getElseExpression() instanceof NullLiteral)) {
				// System.out.println("[DEBUG] Found ternary assignment: " + var.getName());
				// System.out.println("[DEBUG] Ternary condition: " + ternary);
				validRefactors.put(var.getName().toString(), ternary);
			}
		}
		return false;
	}

	private boolean isApplicable(IfStatement ifStmt) {
		Expression ifStmtCondition = ifStmt.getExpression();
		// System.out.println("[DEBUG] Analyzing if-statement: " + ifStmtCondition);
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
			if (validRefactors.get(varName.toString()) != null) {
				// System.out.println("[DEBUG] Found indirect null check in if-statement: " +
				// condition);
				return true;
			}
		}
		return false;
	}

	@Override
	public void apply(ASTNode node, ASTRewrite rewriter) {
		if (!(node instanceof IfStatement ifStmt)) {
			return;
		}

		Expression ifStmtCondition = ifStmt.getExpression();
		// System.out.println("[DEBUG] Analyzing if-statement: " + ifStmtCondition);
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

			ConditionalExpression ternary = validRefactors.get(varName.toString());

			// ✅ Now, safely cast to ConditionalExpression
			AST ast = node.getAST();
			ParenthesizedExpression pExpression = ast.newParenthesizedExpression();
			pExpression.setExpression((ConditionalExpression) ASTNode.copySubtree(ast, ternary));

			System.out.println("[DEBUG] [Dereference] Replacing Variable: " + varName);
			System.out.println("[DEBUG] [Dereference] New Value: " + pExpression);

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
		if (validRefactors.get(varName.toString()) != null) {
			validRefactors.remove(varName.toString());
		}
	}
}
