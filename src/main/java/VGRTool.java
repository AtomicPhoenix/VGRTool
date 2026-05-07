import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.checkerframework.checker.nullness.qual.NonNull;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

/**
 * Program entrypoint class; Runs the refactoring engine on given source code
 */
@Command(name = "VGRTool", mixinStandardHelpOptions = true, version = "0.1", description = "Program entrypoint class; Runs the refactoring engine on given source code")
public class VGRTool implements Runnable {

	private static final Logger LOGGER = LogManager.getLogger();

	// List of valid refactoring module names for Picocli
	static class ValidRefactoringModules extends ArrayList<String> {
		ValidRefactoringModules() {
			super(List.of(AddNullCheckBeforeDereferenceRefactoring.NAME, BooleanFlagRefactoring.NAME,
					SentinelRefactoring.NAME, NestedNullRefactoring.NAME, "All"));
		}
	}

	// Picocli automatically assigns values to arguments during runtime,
	// guaranteeing initialization
	@SuppressWarnings("initialization.field.uninitialized")
	// First position argument represents target directory
	@Parameters(index = "0", description = "Path of directory to execute program on")
	private String targetDir;

	// Picocli automatically assigns values to arguments during runtime,
	// guaranteeing initialization
	@SuppressWarnings("initialization.field.uninitialized")
	// All remaining positional arguments are parsed as module names.
	@Parameters(index = "1", arity = "1..*", description = "Refactoring module(s) to use. Valid values: ${COMPLETION-CANDIDATES}", completionCandidates = ValidRefactoringModules.class)
	private List<String> refactoringModules;

	@Option(names = "--search", description = "Only search for valid refactorings without actually refactoring")
	private static boolean searchOnly = false;

	@Option(names = "--unsound", description = "Allow potentially unsound refactorings")
	public static boolean unsound = false;

	// Parses command-line arguments and executes run()
	public static void main(String[] args) {
		int exitCode = new CommandLine(new VGRTool()).execute(args);
		System.exit(exitCode);
	}

	/**
	 * Main method for the program; Runs refactorings to all Java files in a given
	 * directory
	 */
	public void run() {
		LOGGER.debug("Processing directory: {}", targetDir);
		LOGGER.debug("Selected Refactoring Module(s): {}", refactoringModules);

		try {
			// Step 1: Collect all Java files in the target directory
			List<File> javaFiles = getJavaFiles(targetDir);

			// Step 2: Process each Java file using the selected refactoring module(s)
			for (File file : javaFiles) {
				LOGGER.debug("Processing file: {}", file.getPath());
				List<Refactoring> selectedRefactorings = processRefactorings(refactoringModules);
				processFile(file, selectedRefactorings);
			}

			LOGGER.info("Refactoring completed successfully!");
		} catch (IOException e) {
			LOGGER.error("Encountered an error while attempting refactoring", e);
		}
	}

	/**
	 * Parses and converts {@value refactoringModuleNames} into a list of
	 * refactorings without duplicates.
	 *
	 * @param refactoringModuleNames
	 *            A List<String> of refactoringModule names to parse
	 **/
	private static List<Refactoring> processRefactorings(List<String> refactoringModuleNames) {
		List<Refactoring> refactorings = new ArrayList<>();
		for (String name : refactoringModuleNames) {
			switch (name) {
				case AddNullCheckBeforeDereferenceRefactoring.NAME ->
					refactorings.add(new AddNullCheckBeforeDereferenceRefactoring());
				case BooleanFlagRefactoring.NAME -> refactorings.add(new BooleanFlagRefactoring());
				case SentinelRefactoring.NAME -> refactorings.add(new SentinelRefactoring());
				case NestedNullRefactoring.NAME -> refactorings.add(new NestedNullRefactoring());
				case "All" -> refactorings.addAll(Arrays.asList(new AddNullCheckBeforeDereferenceRefactoring(),
						new BooleanFlagRefactoring(), new SentinelRefactoring(), new NestedNullRefactoring()));
				// Should already be caught by Picocli
				default -> throw new IllegalArgumentException("Unknown refactoring module: " + name);
			}
		}

		if (refactorings.isEmpty()) {
			throw new IllegalArgumentException("No valid refactorings specified");
		}
		return refactorings;
	}

	/**
	 * Returns a list of all java files in the given directory path
	 * 
	 * @param directory
	 *            Filepath of directory to search through (non-recursive)
	 */
	private static @NonNull List<File> getJavaFiles(String directory) throws IOException {
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
	 * @param refactoringModules
	 *            the refactoring to apply to the file
	 */
	private static void processFile(File file, List<Refactoring> refactoringModules) {
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

			// Step 5: Initialize RefactoringEngine with the selected modules
			RefactoringEngine refactoringEngine = new RefactoringEngine(refactoringModules);

			// Step 6: Search or apply refactorings using RefactoringEngine
			if (searchOnly) {
				String filePath = file.getPath();
				String fileName = file.getName();
				refactoringEngine.searchRefactorings(cu, content, filePath, fileName);
				return;
			}
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
