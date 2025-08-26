import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

public abstract class Refactoring {
	public abstract boolean isApplicable(ASTNode node);

	public abstract void apply(ASTNode node, ASTRewrite rewriter);

	/**
	 * Recursively analyze an expression to get all the subexpressions that comprise
	 * it. Finds inner expressions of PrefixExpressions, ParenthesizedExpressions,
	 * and InfixExpressions
	 * 
	 * @param expr
	 *            The expression to analyze
	 * @return A list of all subexpressions within an expreesion
	 */
	public static List<Expression> getSubExpressions(Expression expr) {
		List<Expression> exprList = new ArrayList<Expression>();

		if (expr instanceof ParenthesizedExpression pExpr) {
			exprList.addAll(getSubExpressions(pExpr.getExpression()));
		} else if (expr instanceof PrefixExpression pExpr && pExpr.getOperator() == PrefixExpression.Operator.NOT) {
			exprList.addAll(getSubExpressions(pExpr.getOperand()));
		} else if (expr instanceof InfixExpression infix
				&& (infix.getOperator().equals(InfixExpression.Operator.CONDITIONAL_AND)
						|| infix.getOperator().equals(InfixExpression.Operator.CONDITIONAL_OR))) {
			exprList.addAll(getSubExpressions(infix.getLeftOperand()));
			exprList.addAll(getSubExpressions(infix.getRightOperand()));
		} else {
			exprList.add(expr);
		}

		return exprList;
	}
}
