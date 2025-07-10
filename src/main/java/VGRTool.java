import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.MethodInvocation;

/**
 * Program entrypoint class; Runs the refactoring engine on given source code
 */
public class VGRTool {

	/**
	 * Main method for the program; Runs refactorings to all Java files in a given
	 * directory
	 * 
	 * @param args Path of directory to execute program on (args[0]) and
	 *             refactoring modules to use (args[1...])
	 */
	public static void main(String[] args) {
		if (args.length < 2) {
			System.out.println("Usage: java VGRTool <sourceDirPath> <refactoringModules>");
			System.out.println("Available Modules:");
			System.out.println(" - AddNullCheckBeforeDereferenceRefactoring");
			System.out.println(" - BooleanFlagRefactoring");
			System.out.println(" - NestedNullRefactoring");
			System.out.println(" - SentinelRefactoring");
			System.out.println(" - SeperateVariableRefactoring");
			System.out.println(" - All");
			System.exit(1);
		}

		String targetDir = args[0];
		String refactoringModule = args[1];

		System.out.println("Processing directory: " + targetDir);
		System.out.println("Selected Refactoring Module: " + refactoringModule);

		try {
			// Step 1: Collect all Java files in the target directory
			List<File> javaFiles = getJavaFiles(targetDir);

			// Step 2: Process each Java file using the selected refactoring module
			for (File file : javaFiles) {
				System.out.println("Processing file: " + file.getPath());
				processFile(file, refactoringModule);
			}

			System.out.println("Refactoring completed successfully!");
		} catch (IOException e) {
			System.out.println(e.toString());
		}
	}

	/**
	 * Returns a list of all java files in the given directory path
	 * 
	 * @param directory Filepath of directory to search through (non-recursive)
	 */
	private static List<File> getJavaFiles(String directory) throws IOException {
		List<File> javaFiles = new ArrayList<>();

		Files.walk(Paths.get(directory)).filter(path -> path.toString().endsWith(".java"))
				.forEach(path -> javaFiles.add(path.toFile()));
		return javaFiles;
	}

	/**
	 * Applies refactoring to a given file
	 * 
	 * @param file              The file to refactor
	 * @param refactoringModule the refactoring to apply to the file
	 */
	private static void processFile(File file, String refactoringModule) {
		try {
			// Step 3: Read the file content
			String content = Files.readString(file.toPath());

			// Unsure what this line was meant for?
			// Document document = new Document(content);

			// Step 4: Parse the content into an AST
			@SuppressWarnings("deprecation")
			ASTParser parser = ASTParser.newParser(AST.JLS17); // Use Java 17
			parser.setSource(content.toCharArray());
			CompilationUnit cu = (CompilationUnit) parser.createAST(null);

			// Step 5: Initialize RefactoringEngine with the selected module
			List<String> selectedModules = Collections.singletonList(refactoringModule);
			RefactoringEngine refactoringEngine = new RefactoringEngine(selectedModules);

			// Step 6: Apply refactorings using RefactoringEngine
			String refactoredSourceCode = refactoringEngine.applyRefactorings(cu, content);

			// Step 7: Write the refactored code back to the file
			Files.writeString(file.toPath(), refactoredSourceCode);
			System.out.println("Refactored file saved: " + file.getPath());

		} catch (IOException e) {
			String logString = "Error processing file: " + file.getPath();
			System.out.println(logString);
		}
	}
}
