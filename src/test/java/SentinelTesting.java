import org.junit.jupiter.api.Test;

/**
 * Class to perform JUnit tests on the SentinelTesting refactoring module
 * 
 * @see SentinelTesting
 */
public class SentinelTesting {

	public void test(String input, String expectedOutput) {
		TestingEngine.testSingleRefactoring(input, expectedOutput, "SentinelRefactoring");
	}

	@Test
	public void simpleTest() {
		String input = """
				public class SentinelTest {
				    public void test() {
				        String str = "Hello World";
				        int val = 0;

				        if (str == null) {
				            val = -1;
				        }

				        if (val == -1) {
				            System.out.println("ERROR: str is null");
				        }
				    }
				}
				        """;
		String expectedOutput = """
				public class SentinelTest {
				    public void test() {
				        String str = "Hello World";
				        int val = 0;

				        if (str == null) {
				            val = -1;
				        }

				        if (str == null) {
				            System.out.println("ERROR: str is null");
				        }
				    }
				}
				                        """;
		test(input, expectedOutput);
	}

	@Test
	public void swappedSignsTest() {
		String input = """
				public class SentinelTest {
				    public void test() {
				        String str = "Hello World";
				        int val = 0;

				        if (str == null) {
				            val = -1;
				        }

				        if (val != -1) {
				            System.out.println("ERROR: str is null");
				        }
				    }
				}
				        """;
		String expectedOutput = """
				public class SentinelTest {
				    public void test() {
				        String str = "Hello World";
				        int val = 0;

				        if (str == null) {
				            val = -1;
				        }

				        if (str != null) {
				            System.out.println("ERROR: str is null");
				        }
				    }
				}
				                        """;
		test(input, expectedOutput);
	}

	@Test
	public void inverseCheck() {
		String input = """
				public class SentinelTest {
				    public void test() {
				        String str = "Hello World";
				        int val = 0;

				        if (str != null) {
				            val = 1;
				        }

				        if (val == 1) {
				            System.out.println("Str is not null");
				        }
				    }
				}
				        """;
		String expectedOutput = """
				public class SentinelTest {
				    public void test() {
				        String str = "Hello World";
				        int val = 0;

				        if (str != null) {
				            val = 1;
				        }

				        if (str != null) {
				            System.out.println("Str is not null");
				        }
				    }
				}
				                        """;
		test(input, expectedOutput);
	}

	@Test
	public void indirectCheck() {
		String input = """
				public class SentinelTest {
				    public void test() {
				        String str = "Hello World";
				        int val = 0;

				        if (str == null) {
				            val = -1;
				        }

				        if (val == 0) {
				            System.out.println("Str is not null");
				        }
				    }
				}
				        """;
		String expectedOutput = """
				public class SentinelTest {
				    public void test() {
				        String str = "Hello World";
				        int val = 0;

				        if (str == null) {
				            val = -1;
				        }

				        if (str != null) {
				            System.out.println("Str is not null");
				        }
				    }
				}
				                        """;
		test(input, expectedOutput);
	}
}
