import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.TextEdit;

/**
 * Class to run VGRs on an AST
 */
public class RefactoringEngine {
	/**
	 * List of refactorings to apply
	 */
	private final List<Refactoring> refactorings;

	/**
	 * Default Constructor
	 * 
	 * @param refactoringNames A list of Refactorings to use
	 */
	public RefactoringEngine(List<String> refactoringNames) {
		refactorings = new ArrayList<>();

		for (String name : refactoringNames) {
			switch (name) {
				case "AddNullCheckBeforeDereferenceRefactoring" ->
					refactorings.add(new AddNullCheckBeforeDereferenceRefactoring());
				case "BooleanFlagRefactoring" -> refactorings.add(new BooleanFlagRefactoring());
				case "NestedNullRefactoring" -> refactorings.add(new NestedNullRefactoring());
				case "SentinelRefactoring" -> refactorings.add(new SentinelRefactoring());
				case "SeperateVariableRefactoring" ->
					refactorings.add(new SeperateVariableRefactoring());
				default -> System.err.println("Unknown refactoring: " + name);
			}
		}

		if (refactorings.isEmpty()) {
			System.err.println("No valid refactorings specified. Exiting.");
			System.exit(1);
		}
	}

	/**
	 * Applies all refactorings in {@value refactorings} to a given source file
	 * 
	 * @param cu         The compilation unit to use
	 * @param sourceCode A string representing the filepath of the source code to
	 *                   refactor
	 */
	public String applyRefactorings(CompilationUnit cu, String sourceCode) {
		AST ast = cu.getAST();
		ASTRewrite rewriter = ASTRewrite.create(ast);

		for (Refactoring refactoring : refactorings) {
			cu.accept(new ASTVisitor() {
				@Override
				public void preVisit(ASTNode node) {
					System.out.println("[DEBUG] Visiting AST Node: "
							+ node.getClass().getSimpleName());

					if (refactoring.isApplicable(node)) {
						System.out.println("[DEBUG] Applying refactoring to: \n" + node);
						refactoring.apply(node, rewriter);
					}
				}
			});
		}

		Document document = new Document(sourceCode);
		TextEdit edits = rewriter.rewriteAST(document, null);
		try {
			edits.apply(document);
		} catch (MalformedTreeException | org.eclipse.jface.text.BadLocationException e) {
			System.out.println(e.toString());
		}

		return document.get();
	}

}
