
import org.junit.jupiter.api.Test;

/**
 * Class to perform JUnit tests on the BooleanFlagRefactoring refactoring module
 * 
 * @see BooleanFlagRefactoring
 */
public class BooleanFlagTesting {

	public void test(String input, String expectedOutput) {
		TestingEngine.testSingleRefactoring(input, expectedOutput, "BooleanFlagRefactoring");
	}

	@Test
	public void simpleTest() {
		String input = """
				public class BooleanFlagTest {
				  public void testMethod(String x) {
				    boolean xIsNull = x == null;
				    if (xIsNull) {
				      ;
				    }
				  }
				}
				""";
		String expectedOutput = """
				public class BooleanFlagTest {
				  public void testMethod(String x) {
				    boolean xIsNull = x == null;
				    if ((x == null)) {
				      ;
				    }
				  }
				}
				""";
		test(input, expectedOutput);
	}

	@Test
	public void swappedSignsTest() {
		String input = """
				public class BooleanFlagTest {
				  public void testMethod(String x) {
				    boolean xIsNotNull = x != null;
				    if (xIsNotNull) {
				      ;
				    }
				  }
				}
				""";
		String expectedOutput = """
				public class BooleanFlagTest {
				  public void testMethod(String x) {
				    boolean xIsNotNull = x != null;
				    if ((x != null)) {
				      ;
				    }
				  }
				}
				""";
		test(input, expectedOutput);
	}

	@Test
	public void inverseFlagTest1() {
		String input = """
				public class BooleanFlagTest {
				  public void testMethod(String x) {
				    boolean xIsNotNull = x != null;
				    if (!xIsNotNull) {
				      ;
				    }
				  }
				}
				""";
		String expectedOutput = """
				public class BooleanFlagTest {
				  public void testMethod(String x) {
				    boolean xIsNotNull = x != null;
				    if (!(x != null)) {
				      ;
				    }
				  }
				}
				""";
		test(input, expectedOutput);
	}

	@Test
	public void inverseFlagTest2() {
		String input = """
				public class BooleanFlagTest {
				  public void testMethod(String x) {
				    boolean xIsNotNull = x != null;
				    if (!xIsNotNull) {
				      ;
				    }
				  }
				}
				""";
		String expectedOutput = """
				public class BooleanFlagTest {
				  public void testMethod(String x) {
				    boolean xIsNotNull = x != null;
				    if (!(x != null)) {
				      ;
				    }
				  }
				}
				""";
		test(input, expectedOutput);
	}

	@Test
	public void andConditionTest() {
		String input = """
				public class BooleanFlagTest {
				  public void testMethod(String x) {
				    boolean xIsNull = x == null;
				    if (xIsNull && 1 > 0) {
				      ;
				    }
				  }
				}
				""";
		String expectedOutput = """
				public class BooleanFlagTest {
				  public void testMethod(String x) {
				    boolean xIsNull = x == null;
				    if ((x == null) && 1 > 0) {
				      ;
				    }
				  }
				}
				""";
		test(input, expectedOutput);
	}

	@Test
	public void ternaryTest() {
		String input = """
				class TernaryBooleanFlagTest {
				    @SuppressWarnings("all")
				    void test() {
				        boolean xIsNull = (handlerMethod == null ? true : false);
				        Object exceptionHandlerObject = null;
				        Method exceptionHandlerMethod = null;

				        if (xIsNull) {
				           ;
				        }
				    }
				}
				""";
		String expectedOutput = """
				class TernaryBooleanFlagTest {
				    @SuppressWarnings("all")
				    void test() {
				        boolean xIsNull = (handlerMethod == null ? true : false);
				        Object exceptionHandlerObject = null;
				        Method exceptionHandlerMethod = null;

				        if (((handlerMethod == null ? true : false))) {
				           ;
				        }
				    }
				}
				""";
		test(input, expectedOutput);
	}

	@Test
	public void inverseTernaryTest() {
		String input = """
				class TernaryBooleanFlagTest {
				    @SuppressWarnings("all")
				    void test() {
				        boolean xIsNotNull = (handlerMethod != null ? true : false);
				        Object exceptionHandlerObject = null;
				        Method exceptionHandlerMethod = null;

				        if (!xIsNotNull) {
				           ;
				        }
				    }
				}
				""";
		String expectedOutput = """
				class TernaryBooleanFlagTest {
				    @SuppressWarnings("all")
				    void test() {
				        boolean xIsNotNull = (handlerMethod != null ? true : false);
				        Object exceptionHandlerObject = null;
				        Method exceptionHandlerMethod = null;

				        if (!((handlerMethod != null ? true : false))) {
				           ;
				        }
				    }
				}
				""";
		test(input, expectedOutput);
	}

	@Test
	public void newContainerTest() {
		String input = """
				public class NewContainerTest {
				    List<String> items = Arrays.asList("Hello World");

				    public void test() {
				        boolean hasItems = (items != null && !items.isEmpty());

				        // Indirectly implies items != null
				        if (hasItems) {
				            TreeSet<?> set = new TreeSet<>(items);
				        }
				    }
				}
				""";
		String expectedOutput = """
				public class NewContainerTest {
				    List<String> items = Arrays.asList("Hello World");

				    public void test() {
				        boolean hasItems = (items != null && !items.isEmpty());

				        // Indirectly implies items != null
				        if (((items != null && !items.isEmpty()))) {
				            TreeSet<?> set = new TreeSet<>(items);
				        }
				    }
				}
				""";
		test(input, expectedOutput);
	}
}
