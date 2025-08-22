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

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.MethodInvocation;

public class VGRTool {
	private static final Logger logger = Logger.getLogger(VGRTool.class.getName());

	public static void main(String[] args) {
		if (args.length < 2) {
			System.out.println("Usage: java VGRTool <sourceDirPath> <refactoringModule>");
			System.out.println("Available Modules:");
			System.out.println(" - WrapWithCheckNotNullRefactoring");
			System.out.println(" - AddNullChecksForNullableReferences");
			System.out.println(" - DereferenceRefactoring");
			System.out.println(" - IntroduceLocalVariableAndNullCheckRefactoring");
			System.out.println(" - IntroduceLocalVariableWithNullCheckRefactoring");
			System.out.println(" - SimplifyNullCheckRefactoring");
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
			logger.log(Level.WARNING, e.toString());
		}
	}

	private static List<File> getJavaFiles(String directory) throws IOException {
		List<File> javaFiles = new ArrayList<>();

		Files.walk(Paths.get(directory)).filter(path -> path.toString().endsWith(".java"))
				.forEach(path -> javaFiles.add(path.toFile()));
		return javaFiles;
	}

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

			// Step 5: Extract nullable expressions
			Set<Expression> nullableExpressions = extractExpressionsPossiblyNull(cu);

			// Step 6: Initialize RefactoringEngine with the selected module
			List<String> selectedModules = Collections.singletonList(refactoringModule);
			RefactoringEngine refactoringEngine = new RefactoringEngine(selectedModules);

			// Step 7: Apply refactorings using RefactoringEngine
			String refactoredSourceCode = refactoringEngine.applyRefactorings(cu, content);

			// Step 8: Write the refactored code back to the file
			Files.writeString(file.toPath(), refactoredSourceCode);
			System.out.println("Refactored file saved: " + file.getPath());

		} catch (IOException e) {
			String logString = "Error processing file: " + file.getPath();
			logger.log(Level.WARNING, logString);
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
