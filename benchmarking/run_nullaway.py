# pyright: basic
"""
This script runs error-prone on all the benchmarks.
Fill in the correct values for the macros at
the top of the file before executing.

The first step is to get Error Prone running on your build, as documented here. Then, you need to get the NullAway jar on the annotation processor path for the Javac invocations where Error Prone is running. Finally, you need to pass the appropriate compiler arguments to configure NullAway (at the least, the -XepOpt:NullAway:AnnotatedPackages option).
"""

from codecs import ignore_errors
import os
import shutil
import subprocess
import re
import sys
import pandas as pd

# DATASETS_FOLDER = "../mini-datasets"
BENCHMARKING_FOLDER = "./benchmarking"

DATASETS_FOLDER = f"{BENCHMARKING_FOLDER}/datasets"
DATASETS_CACHE_FOLDER = f"{DATASETS_FOLDER}/cache"
DATASETS_REFACTORED_FOLDER = f"{DATASETS_FOLDER}/refactored"


RESULTS_FOLDER = f"{BENCHMARKING_FOLDER}/results"
SUMMARY_CSV = f"{RESULTS_FOLDER}/summary.csv"
COMPILED_CLASSES_FOLDER = f"{BENCHMARKING_FOLDER}/compiled_classes"
SRC_FOLDER = f"{BENCHMARKING_FOLDER}/sources"
ERRORPRONE_EXPORTS = "  -J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED  -J--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED  -J--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED  -J--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED  -J--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED  -J--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED  -J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED  -J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED  -J--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED  -J--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED "

JAR_FOLDER = f"{BENCHMARKING_FOLDER}/tools"
JARS = ":".join(
    [
        f"{JAR_FOLDER}/errorprone/error_prone_core-2.38.0-with-dependencies.jar",
        f"{JAR_FOLDER}/errorprone/dataflow-errorprone-3.49.3-eisop1.jar",
        f"{JAR_FOLDER}/errorprone/jFormatString-3.0.0.jar",
        f"{JAR_FOLDER}/nullaway/nullaway-0.12.7.jar",
        f"{JAR_FOLDER}/nullaway/dataflow-nullaway-3.49.5.jar",
        f"{JAR_FOLDER}/nullaway/checker-qual-3.49.2.jar",
    ]
)
ERRORPRONE_DIR = f"{BENCHMARKING_FOLDER}/tools/errorprone"
NULLAWAY_DIR = f"{BENCHMARKING_FOLDER}/tools/nullaway"
SKIP_COMPLETED = False  # skips if the output file is already there.
TIMEOUT = 1800
TIMEOUT_CMD = "timeout"

# Benchmarking Stage Zero:  Initialize folders and datasets
# Benchmarking Stage One:   Running NullAway on unmodified NJR-1 Dataset
# Benchmarking Stage Two:   Refactoring NJR-1 Dataset with VGRTool
# Benchmarking Stage Three: Running NullAway on refactored NJR-1 Dataset
# Benchmarking Stage Four:  Analyze and save data
benchmark_results = pd.DataFrame(
    columns=["benchmark", "initial_error_count", "refactored_error_count"]
)


def stage_zero():
    print("Benchmarking Stage Zero:  Initialize folders and datasets")
    os.makedirs(SRC_FOLDER, exist_ok=True)
    os.makedirs(COMPILED_CLASSES_FOLDER, exist_ok=True)
    os.makedirs(RESULTS_FOLDER, exist_ok=True)
    os.makedirs(DATASETS_FOLDER, exist_ok=True)
    os.makedirs(DATASETS_CACHE_FOLDER, exist_ok=True)

    # Download datasets if they don't already exist
    if len(os.listdir(DATASETS_CACHE_FOLDER)) == 0:
        print("Downloading Datasets...")

        # Download NJR-1 Dataset
        res = os.system(
            f"wget https://zenodo.org/records/4632231/files/njr-1_dataset.zip -P {DATASETS_CACHE_FOLDER}"
        )
        if res != 0:
            print(f"Downloading datasets failed with exit code {res}. Exiting Program")
            sys.exit(1)

        # Extract zip file
        res = os.system(
            f"unzip {DATASETS_CACHE_FOLDER}/njr-1_dataset.zip -d {DATASETS_CACHE_FOLDER} && rm {DATASETS_CACHE_FOLDER}/njr-1_dataset.zip && mv {DATASETS_CACHE_FOLDER}/njr-1_dataset/* {DATASETS_CACHE_FOLDER} && rmdir {DATASETS_CACHE_FOLDER}/njr-1_dataset/"
        )
        if res != 0:
            print(
                f"Extracting downloaded datasets failed with exit code {res}. Exiting Program"
            )
            sys.exit(1)
    print("Benchmarking Stage Zero Completed\n")


def stage_one():
    global benchmark_results
    print("Benchmarking Stage One: Running NullAway on unmodified NJR-1 Dataset")
    results = run_benchmark(DATASETS_CACHE_FOLDER)
    rows = []
    for benchmark, value in results.items():
        rows.append(
            {
                "benchmark": benchmark,
                "initial_error_count": value,
                "refactored_error_count": None,
            }
        )

    benchmark_results = pd.DataFrame(rows)
    print("Benchmarking Stage One Completed\n")


def stage_two():
    print("Benchmarking Stage Two: Refactoring NJR-1 Dataset with VGRTool")
    shutil.rmtree(DATASETS_REFACTORED_FOLDER, ignore_errors=True)
    res = os.system(f"cp -r {DATASETS_CACHE_FOLDER} {DATASETS_REFACTORED_FOLDER}")
    if res != 0:
        print(
            f"Copying datasets for refactoring failed with exit code {res}. Exiting Program"
        )
        sys.exit(1)
    res = os.system(f"./gradlew run --args='{DATASETS_REFACTORED_FOLDER} All'")
    if res != 0:
        print(f"Running VGRTool failed with exit code {res}. Exiting Program")
        sys.exit(1)
    print("Benchmarking Stage Two Completed\n")


def stage_three():
    global benchmark_results
    print("Benchmarking Stage Three: Running NullAway on refactored NJR-1 Dataset")
    results = run_benchmark(DATASETS_REFACTORED_FOLDER)

    for benchmark, value in results.items():
        benchmark_results.loc[
            benchmark_results["benchmark"] == benchmark, "refactored_error_count"
        ] = value
    print("Benchmarking Stage Three Completed\n")


def stage_four():
    global benchmark_results

    # 1. Calculate average difference (whole number and percent)
    benchmark_results["error_reduction"] = (
        benchmark_results["initial_error_count"]
        - benchmark_results["refactored_error_count"]
    )
    avg_diff = benchmark_results["error_reduction"].mean()

    benchmark_results["error_reduction_percent"] = benchmark_results.apply(
        lambda row: (
            (row["error_reduction"] / row["initial_error_count"]) * 100
            if row["initial_error_count"] != 0
            else 0
        ),
        axis=1,
    )
    benchmark_results["error_reduction_percent"] = (
        benchmark_results["error_reduction_percent"]
        .astype("Float64")
        .replace([float("inf"), -float("inf")], pd.NA)
    )
    avg_percent_reduction = benchmark_results["error_reduction_percent"].dropna().mean()

    # 3. Save to CSV
    benchmark_results.to_csv(f"{SUMMARY_CSV}")

    print("SUMMARY OF RESULTS:")
    print(f"AVERAGE ERROR REDUCTION: {avg_diff}")
    print(f"AVERAGE ERROR REDUCTION (PERCENT): {avg_percent_reduction}")
    print(
        f"BENCHMARKS WITH LARGEST PERCENT REDUCTION): \n{benchmark_results.sort_values(by='error_reduction_percent', ascending=False).head(10)}"
    )
    print("Benchmarking Stage Four Completed\n")


def run_benchmark(path: str):
    errors: dict[str, int] = {}
    for benchmark in os.listdir(path):
        print(f"Benchmarking {os.path.basename(benchmark)}...")

        # skip non-directories
        if not os.path.isdir(f"{path}/{benchmark}"):
            continue

        # Get a list of Java source code files.
        benchmark_path = os.path.join(path, benchmark)
        find_srcs_command = ["find", f"{benchmark_path}/src", "-name", "*.java"]
        src_file = f"{SRC_FOLDER}/{benchmark}.txt"
        with open(src_file, "w") as f:
            _ = subprocess.run(find_srcs_command, stdout=f)

        find_pkgs_command = (
            f"find {benchmark_path}"
            + " -name \"*.java\" -exec awk 'FNR==1 && /^package/ {print $2}' {} + | sed 's/;//' | sort -u | paste -sd,"
        )

        pkgs = subprocess.run(
            find_pkgs_command, shell=True, capture_output=True
        ).stdout.decode("utf-8")

        # print(pkgs)
        # get folder with libraries used by benchmark
        lib_folder = f"{path}/{benchmark}/lib"

        # execute infer on the source files

        # Split up exports properly
        exports_args = ERRORPRONE_EXPORTS.strip().split()

        # Split the annotated packages
        annotated_pkgs = pkgs.strip()
        annotated_pkgs_arg = f"-XepOpt:NullAway:AnnotatedPackages={annotated_pkgs}"

        # Build javac command as a list
        command = [
            TIMEOUT_CMD,
            str(TIMEOUT),
            "javac",
            *exports_args,
            "-d",
            COMPILED_CLASSES_FOLDER,
            "-cp",
            lib_folder,
            "-XDcompilePolicy=simple",
            "--should-stop=ifError=FLOW",
            "-processorpath",
            JARS,
            f"-Xplugin:ErrorProne {annotated_pkgs_arg} -XepDisableAllChecks -Xep:NullAway:ERROR",
            "-Xmaxerrs",
            "0",
            "-Xmaxwarns",
            "0",
            f"@{src_file}",
        ]
        print(" ".join(command))
        sys.exit()

        result = subprocess.run(command, text=True, capture_output=True)
        with open(f"{RESULTS_FOLDER}/{benchmark}.txt", "w") as result_file:
            _ = result_file.write(result.stderr)

        error_count = len(
            [
                match.start()
                for match in re.finditer("error: \\[NullAway\\]", result.stderr)
            ]
        )

        errors[benchmark] = error_count
        shutil.rmtree(COMPILED_CLASSES_FOLDER, ignore_errors=True)
    return errors


if len(sys.argv) == 1:
    stage_zero()
    stage_one()
    stage_two()
    stage_three()
    stage_four()
elif sys.argv[1] == "summary":
    if os.path.isfile(SUMMARY_CSV):
        df = pd.read_csv(
            SUMMARY_CSV, index_col=0
        )  # index_col=0 preserves row labels if saved that way
        avg_diff = df["error_reduction"].mean()
        avg_percent_reduction = df["error_reduction_percent"].dropna().mean()
        print("SUMMARY OF LAST RUN: ")
        print(f"AVERAGE ERROR REDUCTION: {avg_diff}")
        print(f"AVERAGE ERROR REDUCTION (PERCENT): {avg_percent_reduction}")
        print(
            f"BENCHMARKS WITH LARGEST PERCENT REDUCTION): \n{df.sort_values(by='error_reduction_percent', ascending=False).head(11)}"
        )
    else:
        print("Error getting summary of last run: File {SUMMARY_CSV} does not exist.")
elif sys.argv[1] == "clean":
    shutil.rmtree(DATASETS_REFACTORED_FOLDER, ignore_errors=True)
    shutil.rmtree(RESULTS_FOLDER, ignore_errors=True)
    shutil.rmtree(SRC_FOLDER, ignore_errors=True)
    shutil.rmtree(COMPILED_CLASSES_FOLDER, ignore_errors=True)
elif sys.argv[1] == "full-clean":
    shutil.rmtree(DATASETS_FOLDER, ignore_errors=True)
    shutil.rmtree(RESULTS_FOLDER, ignore_errors=True)
    shutil.rmtree(SRC_FOLDER, ignore_errors=True)
    shutil.rmtree(COMPILED_CLASSES_FOLDER, ignore_errors=True)
else:
    print("Usage: python process_data.py")
    print("Arguments:")
    print(
        "\tsummary:   \tSummarizes results from last benchmark rather than re-running the benchmark (optional)\n"
        "\tclean:     \tRemoves all auto-generated files, excluding NJR-1 Cache (optional)\n"
        "\tfull-clean:\tRemoves all auto-generated files, including NJR-1 Cache (optional)\n"
    )
