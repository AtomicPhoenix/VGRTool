import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

/**
 * This class represents a refactoring in which boolean flags are replaced with
 * explicit null checks
 */
public class BooleanFlagRefactoring extends Refactoring {

	/**
	 * List of variable names identified as boolean flags, along with their
	 * corresponding initializer expression
	 */
	private final Dictionary<String, Expression> booleanFlags;

	/** Default constructor (for RefactoringEngine integration) */
	public BooleanFlagRefactoring() {
		super();
		this.booleanFlags = new Hashtable<>();
	}

	@Override
	public boolean isApplicable(ASTNode node) {

		// 1. Check if node is declaration of boolean flag
		if (node instanceof VariableDeclarationStatement stmt) {

			Type stmtType = stmt.getType();
			boolean isBooleanDeclaration = (stmtType instanceof PrimitiveType pType
					&& pType.getPrimitiveTypeCode() == PrimitiveType.BOOLEAN);

			// 1a. Must be boolean variable
			if (!isBooleanDeclaration)
				return false;

			boolean flagFound = false;

			// 1b. Search through all declared variables in declaration node for a
			// booleanflag
			for (int i = 0; i < stmt.fragments().size(); ++i) {
				VariableDeclarationFragment frag = (VariableDeclarationFragment) stmt.fragments().get(i);
				SimpleName varName = frag.getName();
				Expression varInitializer = frag.getInitializer();
				List<Expression> initExpr = Refactoring.parseExpression(varInitializer);
				for (Expression expression : initExpr) {
					if (expression instanceof ConditionalExpression cExpr) {
						expression = cExpr.getExpression();
					}
					if (expression instanceof InfixExpression infix) {
						if (infix.getOperator() == InfixExpression.Operator.NOT_EQUALS
								|| infix.getOperator() == InfixExpression.Operator.EQUALS) {
							Expression leftOperand = infix.getLeftOperand();
							Expression rightOperand = infix.getRightOperand();
							if ((leftOperand instanceof SimpleName && rightOperand instanceof NullLiteral)
									|| (rightOperand instanceof SimpleName && leftOperand instanceof NullLiteral)) {

								AST ast = node.getAST();
								ParenthesizedExpression pExpr = ast.newParenthesizedExpression();
								Expression copiedExpression = (Expression) ASTNode.copySubtree(ast, varInitializer);
								pExpr.setExpression(copiedExpression);
								booleanFlags.put(varName.toString(), pExpr);
								flagFound = true;
							}
						}
					}
				}
			}
			return flagFound;
		}

		// 2. Check if node is an IfStatement with a boolean flag as part of it's
		// conditional expression
		if (node instanceof IfStatement ifStmt) {
			Expression condition = ifStmt.getExpression();
			List<Expression> exprFragments = Refactoring.parseExpression(condition);
			for (Expression expr : exprFragments) {
				if (expr instanceof InfixExpression infix && (infix.getOperator() == InfixExpression.Operator.NOT_EQUALS
						|| infix.getOperator() == InfixExpression.Operator.EQUALS)) {
					Expression leftOperand = infix.getLeftOperand();
					Expression rightOperand = infix.getRightOperand();

					if ((leftOperand instanceof SimpleName lhs && booleanFlags.get(lhs.toString()) != null)
							|| (rightOperand instanceof SimpleName rhs && booleanFlags.get(rhs.toString()) != null)) {
						return true;
					}
				}
				if (expr instanceof SimpleName sn && booleanFlags.get(sn.toString()) != null) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void apply(ASTNode node, ASTRewrite rewriter) {
		if (node instanceof IfStatement ifStmt) {
			Expression condition = ifStmt.getExpression();
			List<Expression> exprFragments = Refactoring.parseExpression(condition);
			for (Expression expression : exprFragments) {
				if (expression instanceof InfixExpression infix
						&& (infix.getOperator() == InfixExpression.Operator.NOT_EQUALS
								|| infix.getOperator() == InfixExpression.Operator.EQUALS)) {
					Expression leftOperand = infix.getLeftOperand();
					Expression rightOperand = infix.getRightOperand();

					if ((leftOperand instanceof SimpleName var)) {
						Expression newExpr = booleanFlags.get(var.toString());
						rewriter.replace(leftOperand, newExpr, null);
					} else if (rightOperand instanceof SimpleName var) {
						Expression newExpr = booleanFlags.get(var.toString());
						rewriter.replace(rightOperand, newExpr, null);
					}
				}
				if (expression instanceof SimpleName sn) {
					Expression newExpr = booleanFlags.get(sn.toString());
					rewriter.replace(sn, newExpr, null);

				}
			}
		}
	}

}
