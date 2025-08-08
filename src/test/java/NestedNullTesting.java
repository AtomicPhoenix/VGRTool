
import org.junit.jupiter.api.Test;

/**
 * Class to perform JUnit tests on the NestedNullRefactoring refactoring module
 * 
 * @see NestedNullRefactoring
 */
public class NestedNullTesting {

	public void test(String input, String expectedOutput) {
		TestingEngine.testSingleRefactoring(input, expectedOutput, "NestedNullRefactoring");
	}

	@Test
	public void simpleTest() {
		String input = """
				public class NestedNullTest {
				    String str = "Hello World";

				    public boolean checkNull() {
				        return str != null;
				    }

				    public void test() {

				        if (checkNull()) {
				            ;
				        }

				        if (!checkNull()) {
				            ;
				        }
				    }
				}
				""";
		String expectedOutput = """
				public class NestedNullTest {
				    String str = "Hello World";

				    public boolean checkNull() {
				        return str != null;
				    }

				    public void test() {

				        if (str != null) {
				            ;
				        }

				        if (!(str != null)) {
				            ;
				        }
				    }
				}
				""";
	}
}
