import java.util.List;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

/**
 * This class represents a refactoring in which explicit null checks are added
 * before a value dereference
 */
public class AddNullCheckBeforeDereferenceRefactoring extends Refactoring {
	public static final String NAME = "AddNullCheckBeforeDereferenceRefactoring";

	/**
	 * Optional list of expressions identified as possibly null (to guide
	 * applicability)
	 */
	@SuppressWarnings("unused")
	private List<Expression> possiblyNullExpressions;

	/** Default constructor (for RefactoringEngine integration) */
	public AddNullCheckBeforeDereferenceRefactoring() {
		super();
	}

	@Override
	public boolean isApplicable(ASTNode node) {
		System.out.println("[DEBUG] Checking if node is applicable: " + node.getClass().getSimpleName());

		if (node instanceof MethodInvocation || node instanceof FieldAccess || node instanceof QualifiedName
				|| node instanceof ArrayAccess) {
			return true;
		}

		if (node instanceof IfStatement ifStmt) {
			Expression condition = ifStmt.getExpression();
			if (condition instanceof InfixExpression infix
					&& (infix.getOperator() == InfixExpression.Operator.NOT_EQUALS
							|| infix.getOperator() == InfixExpression.Operator.EQUALS)) {
				Expression leftOperand = infix.getLeftOperand();
				Expression rightOperand = infix.getRightOperand();

				if ((leftOperand instanceof SimpleName && rightOperand instanceof NullLiteral)
						|| (rightOperand instanceof SimpleName && leftOperand instanceof NullLiteral)) {
					System.out.println("[DEBUG] Found indirect null check in if-statement: " + condition);
					return true;
				}
			}
		}
		System.out.println("[DEBUG] Node " + node.getClass().getSimpleName() + "is NOT applicable. Skipping.");
		return false;
	}

	@Override
	public void apply(ASTNode node, ASTRewrite rewriter) {
		System.out.println("[DEBUG] Processing ASTNode: " + node.getClass().getSimpleName());

		AST ast = node.getAST();

		if (node instanceof MethodInvocation exprNode) {
			System.out.println("[DEBUG] Target Expression: " + (exprNode).getExpression());
		} else if (node instanceof FieldAccess exprNode) {
			System.out.println("[DEBUG] Target Expression: " + (exprNode).getExpression());
		} else if (node instanceof QualifiedName exprNode) {
			System.out.println("[DEBUG] Target Expression: " + (exprNode).getQualifier());
		} else if (node instanceof ArrayAccess exprNode) {
			System.out.println("[DEBUG] Target Expression: " + (exprNode).getArray());
		} else {
			System.out.println("[DEBUG] Node is not a dereferenceable expression.");
			return;
		}

		ASTNode parentNode = node.getParent();
		VariableDeclarationFragment assignedVariable = null;
		IfStatement existingIfStatement = null;

		// 1️⃣ Find the variable assigned via a ternary operator
		while (parentNode != null) {
			if (parentNode instanceof VariableDeclarationFragment varDecl) {
				Expression initializer = varDecl.getInitializer();

				while (initializer instanceof ParenthesizedExpression) {
					initializer = ((ParenthesizedExpression) initializer).getExpression();
				}

				if (initializer instanceof ConditionalExpression ternary) {
					if (ternary.getElseExpression() instanceof NullLiteral) {
						assignedVariable = varDecl;
						System.out.println("[DEBUG] Found ternary assignment: " + assignedVariable.getName());
						System.out.println("[DEBUG] Ternary condition: " + ternary.getExpression());
					}
				}
				break;
			}
			parentNode = parentNode.getParent();
		}

		if (assignedVariable == null) {
			System.out.println("[DEBUG] No ternary assignment found.");
			return;
		}

		// 2️⃣ Find the if-statement checking the assigned variable
		ASTNode current = assignedVariable.getParent();
		while (current != null) {
			if (current instanceof IfStatement ifStmt) {
				Expression condition = ifStmt.getExpression();

				if (condition instanceof InfixExpression infix) {
					if (infix.getOperator() == InfixExpression.Operator.NOT_EQUALS
							&& infix.getLeftOperand() instanceof SimpleName) {

						SimpleName varName = (SimpleName) infix.getLeftOperand();
						if (varName.getIdentifier().equals(assignedVariable.getName().getIdentifier())) {
							existingIfStatement = ifStmt;
							System.out.println("[DEBUG] Found indirect null check in if-statement: " + condition);
							break;
						}
					}
				}
			}

			// ✅ Instead of going up the AST, we move **forward** in the block
			if (current.getParent() instanceof Block block) {
				List<?> statements = block.statements();
				int index = statements.indexOf(current);

				// Move forward to find an if-statement
				for (int i = index + 1; i < statements.size(); i++) {
					ASTNode nextNode = (ASTNode) statements.get(i);
					if (nextNode instanceof IfStatement) {
						current = nextNode;
						break;
					}
				}
			}
			current = current.getParent();
		}

		if (existingIfStatement != null) {
			// Retrieve initializer and ensure it's not wrapped in a ParenthesizedExpression
			Expression initializer = assignedVariable.getInitializer();

			// ✅ Unwrap ParenthesizedExpression before proceeding
			while (initializer instanceof ParenthesizedExpression) {
				initializer = ((ParenthesizedExpression) initializer).getExpression();
			}

			// ✅ Now, safely cast to ConditionalExpression
			if (initializer instanceof ConditionalExpression ternary) {
				Expression directCheckExpr = (Expression) ASTNode.copySubtree(ast, ternary.getExpression());

				System.out.println("[DEBUG] Replacing condition: " + existingIfStatement.getExpression());
				System.out.println("[DEBUG] New condition: " + directCheckExpr);

				rewriter.replace(existingIfStatement.getExpression(), directCheckExpr, null);
			} else {
				if (initializer != null)
					System.out.println("[ERROR] Expected ConditionalExpression but found: "
							+ initializer.getClass().getSimpleName());
			}
		}
	}

}
