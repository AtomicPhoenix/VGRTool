package com;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Program entrypoint class; Runs the refactoring engine on given source code
 */
public class VGRTool {

	private static final Logger LOGGER = LogManager.getLogger();

	/**
	 * Main method for the program; Runs refactorings to all Java files in a given
	 * directory
	 * 
	 * @param args
	 *            Path of directory to execute program on (args[0]) and refactoring
	 *            modules to use (args[1...])
	 */
	public static void main(String[] args) {
		if (args.length < 2) {

			LOGGER.info("Usage: java VGRTool <sourceDirPath> <refactoringModules>");
			LOGGER.info("Available Modules:");
			LOGGER.info(" - " + AddNullCheckBeforeDereferenceRefactoring.NAME);
			LOGGER.info(" - " + NestedNullRefactoring.NAME);
			System.exit(1);
		}

		String targetDir = args[0];
		String refactoringModule = args[1];

		LOGGER.debug("Processing directory: {}", targetDir);
		LOGGER.debug("Selected Refactoring Module: {}", refactoringModule);

		try {
			// Step 1: Collect all Java files in the target directory
			List<File> javaFiles = getJavaFiles(targetDir);

			// Step 2: Process each Java file using the selected refactoring module
			for (File file : javaFiles) {
				LOGGER.debug("Processing file: {}", file.getPath());
				processFile(file, refactoringModule);
			}

			LOGGER.info("Refactoring completed successfully!");
		} catch (IOException e) {
			LOGGER.error("Encountered an error while attempting refactoring", e);
		}
	}

	/**
	 * Returns a list of all java files in the given directory path
	 * 
	 * @param directory
	 *            Filepath of directory to search through (non-recursive)
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
	 * @param file
	 *            The file to refactor
	 * @param refactoringModule
	 *            the refactoring to apply to the file
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
			parser.setResolveBindings(true);
			parser.setBindingsRecovery(true);

			parser.setUnitName(file.getName()); // Required for binding resolution

			// Set classpath and sourcepath
			String[] classpathEntries = {System.getProperty("java.home") + "/lib/rt.jar"}; // JDK classes

			parser.setEnvironment(classpathEntries, null, null, true);
			parser.setCompilerOptions(JavaCore.getOptions());

			CompilationUnit cu = (CompilationUnit) parser.createAST(null);

			// Step 5: Initialize RefactoringEngine with the selected module
			List<String> selectedModules = Collections.singletonList(refactoringModule);
			RefactoringEngine refactoringEngine = new RefactoringEngine(selectedModules);

			// Step 6: Apply refactorings using RefactoringEngine
			String refactoredSourceCode = refactoringEngine.applyRefactorings(cu, content);

			// Step 7: Write the refactored code back to the file
			Files.writeString(file.toPath(), refactoredSourceCode);

			LOGGER.info("Refactored file saved: {}", file.getPath());

		} catch (IOException e) {
			LOGGER.error("Error processing file: {}", file.getPath(), e);
		}
	}

	private static Set<Expression> extractExpressionsPossiblyNull(CompilationUnit cu) {
		Set<Expression> expressions = new HashSet<>();
		cu.accept(new ASTVisitor() {
			@Override
			public boolean visit(MethodInvocation node) {
				if (node.getExpression() != null) {
					expressions.add(node.getExpression());
				}
				return super.visit(node);
			}

			@Override
			public boolean visit(FieldAccess node) {
				if (node.getExpression() != null) {
					expressions.add(node.getExpression());
				}
				return super.visit(node);
			}
		});
		return expressions;
	}
}
