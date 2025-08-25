
import java.util.Collections;
import org.checkerframework.com.google.common.collect.Lists;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Utility class for other testing classes to use to run tests
 */
public class TestingEngine {
	/**
	 * RefactoringEngine to use to run tests
	 */
	private static RefactoringEngine fullEngine = new RefactoringEngine(
			Lists.newArrayList(AddNullCheckBeforeDereferenceRefactoring.NAME, NestedNullRefactoring.NAME));

	// TODO: WRITE VARIANTS FOR SUPPORTED JAVA VERSIONS
	private static ASTParser parser = ASTParser.newParser(AST.getJLSLatest()); // Use appropriate
	// JLS version
	;

	public TestingEngine() {
		parser.setKind(ASTParser.K_COMPILATION_UNIT);
		parser.setResolveBindings(false);
	}

	public static void testAllRefactorings(String input, String expectedOutput) {
		runTest(input, expectedOutput, fullEngine);
	}

	public static void testSingleRefactoring(String input, String expectedOutput, String refactoring) {
		runTest(input, expectedOutput, new RefactoringEngine(Collections.singletonList(refactoring)));
	}

	private static void runTest(String input, String expectedOutput, RefactoringEngine engine) {
		// Set parser source code
		parser.setSource(input.toCharArray());
		parser.setResolveBindings(true);
		parser.setBindingsRecovery(true);

		// The unit name must match the name of the main class declared in the source.
		// For our test cases the class is always Test
		parser.setUnitName("Test.java"); // Required for binding resolution

		// Set classpath and sourcepath
		String[] classpathEntries = {System.getProperty("java.home") + "/lib/rt.jar"}; // JDK classes

		parser.setEnvironment(classpathEntries, null, null, true);
		parser.setCompilerOptions(JavaCore.getOptions());

		// Parse the source code into an AST
		CompilationUnit cu = (CompilationUnit) parser.createAST(null);

		// Apply refactoring
		String result = engine.applyRefactorings(cu, input);

		// Assert that the output matches the expected transformation
		assertEquals(expectedOutput, result);
	}
}
