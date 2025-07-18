import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

public abstract class Refactoring {
	public abstract boolean isApplicable(ASTNode node);
	public abstract void apply(ASTNode node, ASTRewrite rewriter);
}
