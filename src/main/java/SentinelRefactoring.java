import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.InfixExpression.Operator;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * This class represents a refactoring in which integer variables whose values
 * represent the nullness of another variable are refactored into explicit null
 * checks.
 */
public class SentinelRefactoring extends Refactoring {
	public static final String NAME = "SentinelRefactoring";
	private static final Logger LOGGER = LogManager.getLogger();

	/**
	 * Map of all variables which could be sentinels and their associated AST
	 * information.
	 * <p>
	 * Uses each variable's ({@link org.eclipse.jdt.core.dom.IVariableBinding}) as
	 * the key, ensuring global uniqueness. Two variables who have the same name but
	 * have different scopes will have different IBinding instances.
	 */
	private final Map<IBinding, SentinelCandidate> sentinelCandidates;

	/**
	 * Map of all variables which are confirmed valid sentinels.
	 * <p>
	 * Uses each variable's ({@link org.eclipse.jdt.core.dom.IVariableBinding}) as
	 * the key, ensuring global uniqueness. Two variables who have the same name but
	 * have different scopes will have different IBinding instances.
	 */
	private final Map<IBinding, ConfirmedSentinel> confirmedSentinels;

	/**
	 * Set of all sentinel assignments which have already been parsed; Used to
	 * prevent repeated parsing of same sentinel assignment.
	 */
	private final Set<Assignment> sentinelAssignments;

	/**
	 * Helper class for storing the AST element of a potential sentinel reference
	 */
	private class SentinelCandidate {
		public final IBinding binding;
		public @Nullable Object lastValue;

		public SentinelCandidate(IBinding binding, @Nullable Object lastValue) {
			this.binding = binding;
			this.lastValue = lastValue;
		}
	}

	/**
	 * Helper class for storing the AST element of a confirmed sentinel reference
	 * and it's associated AST elements.
	 */
	private class ConfirmedSentinel {
		public IBinding binding;
		/**
		 * The original assignment statement setting the sentinel's value. A null value
		 * indicates the sentinel has not yet been assigned a value
		 */
		public Assignment sentinel_assignment;
		/**
		 * The conditional expression used to decide the value of the sentinel. A null
		 * value indicates a variable which could become a sentinel, but has not yet had
		 * a conditional assignemnt.
		 */
		public InfixExpression null_check;

		/**
		 * The last value assigned to the sentinel; Used for validity tracking. A null
		 * value represents an unknown previous value.
		 */
		public Object null_value;

		public ConfirmedSentinel(IBinding binding, Assignment sentinel_assignment, InfixExpression null_check,
				Object null_value) {
			this.binding = binding;
			this.sentinel_assignment = sentinel_assignment;
			this.null_check = null_check;
			this.null_value = null_value;
		}
	}

	public SentinelRefactoring() {
		super();
		this.sentinelCandidates = new HashMap<>();
		this.confirmedSentinels = new HashMap<>();
		this.sentinelAssignments = new HashSet<>();
	}

	/*
	 * Detects reassignments of existing sentinels. If reassignment is detected,
	 * removes the sentinel from the list of valid sentinels.
	 */
	private void detectReassignment(Assignment assignmentNode) {
		// Skip assignments that initially define a sentinel.
		if (sentinelAssignments.contains(assignmentNode)) {
			return;
		}

		Expression lhs = assignmentNode.getLeftHandSide();
		if (!(lhs instanceof SimpleName varName)) {
			return;
		}

		IBinding binding = varName.resolveBinding();
		if (binding == null) {
			return;
		}

		sentinelCandidates.remove(binding);
		confirmedSentinels.remove(binding);
	}

	/*
	 * Detects sentinels which are shadowed by new local variables and removes them.
	 */
	private void detectShadowing(VariableDeclarationStatement declaration) {
		// Eclipse JDT API guarantees fragments() returns a live
		// List<VariableDeclarationFragment>
		// See
		// https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/VariableDeclarationStatement.html#fragments()
		@SuppressWarnings("unchecked")
		List<VariableDeclarationFragment> fragments = declaration.fragments();
		for (VariableDeclarationFragment fragment : fragments) {
			SimpleName varName = fragment.getName();

			IBinding binding = varName.resolveBinding();
			if (binding == null) {
				continue;
			}

			sentinelCandidates.remove(binding);
			confirmedSentinels.remove(binding);
		}
	}

	/**
	 * Determines whether a possible sentinel value is valid (i.e. safely
	 * refactorable) by analyzing its associated components.
	 * 
	 * @param candidate
	 *            The sentinel candidate being verified
	 * 
	 * @param newValue
	 *            The value assigned to the sentinel when the null_check condition
	 *            is true
	 */
	private boolean isValidSentinel(SentinelCandidate candidate, Object newValue) {

		LOGGER.debug("Validating Sentinel Candidate: %s", candidate);

		// Ensure we are setting the sentinel to a new, distinct value so that we know
		// whether the null_check condition returned true or not.
		if (candidate.lastValue == null) {
			LOGGER.debug("Last value of sentinel candidate, %s, is unknown", candidate);
			return false;
		}
		if (java.util.Objects.equals(candidate.lastValue, newValue)) {
			LOGGER.debug("New value of Sentinel '%s' matches old value.\n\tOld Value: %s\n\tNew Value: %s", candidate,
					candidate.lastValue, newValue);
			return false;
		}

		LOGGER.debug("Sentinel %s is valid.", candidate);
		return true;
	}

	private void updateSentinel(ASTNode node) {
		if (node instanceof VariableDeclaration declaration) {
			updateSentinel(declaration);
		} else if (node instanceof Assignment assign) {
			updateSentinel(assign);
		} else if (node instanceof MethodInvocation || node instanceof SuperMethodInvocation) {
			LOGGER.debug("Clearing all sentinel values due to method invocation...");

			for (SentinelCandidate candidate : sentinelCandidates.values()) {
				candidate.lastValue = null;
			}
		}
	}

	private void updateSentinel(VariableDeclaration declaration) {
		IBinding binding = declaration.getName().resolveBinding();
		if (binding == null) {
			return;
		}

		Expression initializer = declaration.getInitializer();
		if (initializer == null) {
			return;
		}

		Object newValue = initializer.resolveConstantExpressionValue();
		updateSentinel(binding, newValue);
	}

	private void updateSentinel(Assignment statement) {
		if (!(statement.getLeftHandSide() instanceof SimpleName varName)) {
			return;
		}
		IBinding binding = varName.resolveBinding();
		if (binding == null) {
			return;
		}
		Object newValue = statement.getRightHandSide().resolveConstantExpressionValue();
		updateSentinel(binding, newValue);
	}

	private void updateSentinel(IBinding key, Object newValue) {
		SentinelCandidate candidate = sentinelCandidates.get(key);
		if (candidate == null) {
			sentinelCandidates.put(key, new SentinelCandidate(key, newValue));
		} else {
			candidate.lastValue = newValue;
		}
	}

	@Override
	public boolean isApplicable(ASTNode node) {
		updateSentinel(node);
		if (node instanceof Assignment assign) {
			detectReassignment(assign);
		} else if (node instanceof VariableDeclarationStatement declaration) {
			detectShadowing(declaration);
		} else if (node instanceof IfStatement ifStmt) {
			return isApplicable(ifStmt);
		}
		return false;
	}

	/**
	 * Parses IfStatement node to see if it either declares or utilizes a sentinel.
	 * 
	 * @param ifStmt
	 *            The node to parse
	 */
	public boolean isApplicable(IfStatement ifStmt) {
		// Parse IfStatement block for declarations of sentinel candidates.
		detectSentinels(ifStmt);

		List<Expression> exprs = Refactoring.getSubExpressions(ifStmt.getExpression());
		for (Expression expression : exprs) {
			if (expression instanceof InfixExpression infix) {
				if (isApplicable(infix)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Parses InfixExpression node to see if it either declares or utilizes a
	 * sentinel.
	 * 
	 * @param ifStmt
	 *            The node to parse
	 */
	public boolean isApplicable(InfixExpression infix) {
		Expression leftOperand = infix.getLeftOperand();
		Expression rightOperand = infix.getRightOperand();
		InfixExpression.Operator operator = infix.getOperator();

		// Check if the condition does a check on an existing sentinel.
		return isEqualityCheck(operator) && (usesSentinel(leftOperand) || usesSentinel(rightOperand));
	}

	/**
	 * Detects whether an Expression utilizes a sentinel candidate.
	 * 
	 * @param expr
	 *            the Expression to parse
	 */
	private boolean isEqualityCheck(@Nullable Operator operator) {
		return ((operator == InfixExpression.Operator.NOT_EQUALS || operator == InfixExpression.Operator.EQUALS));
	}

	/**
	 * Detects whether an Expression utilizes a sentinel candidate.
	 * 
	 * @param expr
	 *            the Expression to parse
	 */
	private boolean usesSentinel(Expression expr) {
		if (!(expr instanceof SimpleName sentinel_name)) {
			return false;
		}

		IBinding sentinel_binding = sentinel_name.resolveBinding();
		if (sentinel_binding == null) {
			return false;
		}

		return confirmedSentinels.containsKey(sentinel_binding);
	}

	/**
	 * Parses IfStatement node for the creation of sentinels.
	 * 
	 * @param ifStmt
	 *            The node to parse
	 */
	public void detectSentinels(IfStatement ifStmt) {
		// Check if IfStatement conditonal utilizes a null check.
		InfixExpression null_check = parseNullCheck(Refactoring.getSubExpressions(ifStmt.getExpression()));
		if (null_check == null) {
			return;
		}

		if (!(ifStmt.getThenStatement() instanceof Block thenStmt)) {
			return;
		}

		// Eclipse JDT API guarantees statements() returns a live
		// List<Statement>
		// See
		// https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/Block.html#statements()
		@SuppressWarnings("unchecked")
		List<Statement> stmts = thenStmt.statements();

		// Checks that there is only one line in the ifStatement.
		if (stmts.size() != 1) {
			return;
		}

		// Checks that the single line is an assignment statement.
		if (!(stmts.get(0) instanceof ExpressionStatement exprStmt
				&& exprStmt.getExpression() instanceof Assignment sentinel_assignment)) {
			return;
		}

		if (!(sentinel_assignment.getLeftHandSide() instanceof SimpleName var_name)) {
			return;
		}

		if (sentinel_assignment.getOperator() != Assignment.Operator.ASSIGN) {
			return;
		}

		IBinding binding = var_name.resolveBinding();
		if (binding == null) {
			return;
		}

		SentinelCandidate candidate = sentinelCandidates.get(binding);
		if (candidate == null) {
			return;
		}

		Object sentinel_val = sentinel_assignment.getRightHandSide().resolveConstantExpressionValue();

		if (isValidSentinel(candidate, sentinel_val)) {
			ConfirmedSentinel sentinel = new ConfirmedSentinel(binding, sentinel_assignment, null_check, sentinel_val);
			sentinelAssignments.add(sentinel_assignment);
			confirmedSentinels.put(binding, sentinel);
			LOGGER.debug("Parsed Sentinel: %s", sentinel);
		} else {
			LOGGER.debug("New Sentinel is invalid.");
		}
	}

	@Override
	public void apply(ASTNode node, ASTRewrite rewriter) {
		if (!(node instanceof IfStatement ifStmt)) {
			return;
		}

		List<Expression> exprs = Refactoring.getSubExpressions(ifStmt.getExpression());
		for (Expression expression : exprs) {
			if (!(expression instanceof InfixExpression condition)) {
				continue;
			}

			Expression condLeftOperand = condition.getLeftOperand();
			Expression condRightOperand = condition.getRightOperand();
			InfixExpression.Operator cond_op = condition.getOperator();

			if (!isEqualityCheck(cond_op)) {
				continue;
			}

			SimpleName cond_var;
			Expression cond_val;

			if (condLeftOperand instanceof SimpleName varName) {
				cond_var = varName;
				cond_val = condRightOperand;
			} else if (condRightOperand instanceof SimpleName varName) {
				cond_var = varName;
				cond_val = condLeftOperand;
			} else {
				continue;
			}

			IBinding binding = cond_var.resolveBinding();
			if (binding == null) {
				continue;
			}

			ConfirmedSentinel sentinel = confirmedSentinels.get(binding);
			if (sentinel == null) {
				continue;
			}

			InfixExpression.Operator null_check_op = sentinel.null_check.getOperator();
			boolean originalValueMatch = java.util.Objects.equals(sentinel.null_value,
					cond_val.resolveConstantExpressionValue());

			AST ast = node.getAST();
			InfixExpression replacement = (InfixExpression) ASTNode.copySubtree(ast, sentinel.null_check);
			replacement.setOperator(getRefactoredOperator(null_check_op, cond_op, originalValueMatch));
			rewriter.replace(expression, replacement, null);
		}
	}

	/**
	 * Returns the opposite of the given InfixExpression equality operator.
	 */
	private @NonNull Operator reverseOperator(Operator op) {
		if (op == InfixExpression.Operator.EQUALS) {
			return InfixExpression.Operator.NOT_EQUALS;
		} else if (op == InfixExpression.Operator.NOT_EQUALS) {
			return InfixExpression.Operator.EQUALS;
		}
		return op;
	}

	/**
	 * Returns the conditonal operator to use in a refactored null check.
	 */
	public @NonNull Operator getRefactoredOperator(Operator null_check_op, Operator sentinel_check_op,
			boolean originalValueMatch) {
		Operator refactoredOperator = originalValueMatch ? null_check_op : reverseOperator(null_check_op);

		// Adjust if the sentinel check uses a negated operator.
		boolean negatedOperator = (null_check_op != sentinel_check_op && sentinel_check_op == Operator.NOT_EQUALS);
		return negatedOperator ? reverseOperator(refactoredOperator) : refactoredOperator;
	}

	/**
	 * Detects and returns a null check in a list of expressions.
	 * 
	 * @param exprs
	 *            A list of expressions to parse
	 */
	public @Nullable InfixExpression parseNullCheck(List<Expression> exprs) {
		for (Expression expr : exprs) {
			if (expr instanceof InfixExpression null_check_candidate) {
				Expression leftOperand = null_check_candidate.getLeftOperand();
				Expression rightOperand = null_check_candidate.getRightOperand();

				boolean leftVarRightNull = (leftOperand instanceof SimpleName && rightOperand instanceof NullLiteral);
				boolean leftNullRightVar = (rightOperand instanceof SimpleName && leftOperand instanceof NullLiteral);
				boolean validComparison = (leftVarRightNull || leftNullRightVar);
				boolean validOperator = isEqualityCheck(null_check_candidate.getOperator());
				if (validComparison && validOperator) {
					return null_check_candidate;
				}
			}
		}
		return null;
	}
}
