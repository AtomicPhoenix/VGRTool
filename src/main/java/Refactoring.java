package com;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

/**
 * Abstract class representing a refactoring that can be applied to an AST
 */
public abstract class Refactoring {
	/**
	 * Identifies if an AST node could potentially be refactored
	 * 
	 * @param node
	 *            The ASTNode under analysis
	 * @return A boolean representing whether node can be refactored
	 */
	public abstract boolean isApplicable(ASTNode node);

	/**
	 * Applies refactoring to an ASTNode
	 * 
	 * @param node
	 *            The ASTNode to refactor
	 * @param rewriter
	 *            The ASTRewriter to use for rewriting
	 */
	public abstract void apply(ASTNode node, ASTRewrite rewriter);

	/**
	 * Recursively analyzes an expression and returns the boolean comparison
	 * subexpressions that comprise it.
	 * 
	 * @param expr
	 *            The expression to analyze
	 * @return A list of all subexpressions within an expreesion
	 */
	public static List<Expression> getSubExpressions(Expression expr) {
		List<Expression> exprList = new ArrayList<Expression>();

		// Splits all the relevant expression types (PrefixExpressions,
		// ParenthesizedExpressions, and InfixExpressions) into their subexpressions.
		if (expr instanceof ParenthesizedExpression pExpr) {
			// Get inner expression of ParenthesizedExpressions
			exprList.addAll(getSubExpressions(pExpr.getExpression()));
		} else if (expr instanceof PrefixExpression pExpr && pExpr.getOperator() == PrefixExpression.Operator.NOT) {
			// Get inner expression of PrefixExpressions
			exprList.addAll(getSubExpressions(pExpr.getOperand()));
		} else if (expr instanceof InfixExpression infix
				&& (infix.getOperator().equals(InfixExpression.Operator.CONDITIONAL_AND)
						|| infix.getOperator().equals(InfixExpression.Operator.CONDITIONAL_OR))) {
			// Get both expressions on each side of the operator in InfixExpressions using
			// the CONDITIONAL_AND and CONDITIONAL_OR operators.
			exprList.addAll(getSubExpressions(infix.getLeftOperand()));
			exprList.addAll(getSubExpressions(infix.getRightOperand()));
		} else {
			exprList.add(expr);
		}

		return exprList;
	}
}
