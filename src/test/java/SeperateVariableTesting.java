
/**
 * Class to perform JUnit tests on the SeperateVariableRefactoring refactoring
 * module
 * 
 * @see SeperateVariableRefactoring
 */
public class SeperateVariableTesting {

	public void test(String input, String expectedOutput) {
		TestingEngine.testSingleRefactoring(input, expectedOutput, "SeperateVariableRefactoring");
	}

	public void simpleTest() {
		String input = """
				public class SeperateVariableTest {
				    public void printNonNull(@NonNull String str) {
				        System.out.println(str);
				    }

				    public void test() {
				        String str = "Hello World";

				        Object refObject = str != null ? new Object() : null;

				        if (refObject != null) {
				            printNonNull(str);
				        }
				    }
				}
				""";
		String expectedOutput = """
				public class SeperateVariableTest {
				    public void printNonNull(@NonNull String str) {
				        System.out.println(str);
				    }

				    public void test() {
				        String str = "Hello World";

				        Object refObject = str != null ? new Object() : null;

				        if (str != null) {
				            printNonNull(str);
				        }
				    }
				}
				""";
		test(input, expectedOutput);
	}
}
