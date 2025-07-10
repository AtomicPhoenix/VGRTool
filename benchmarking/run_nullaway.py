# pyright: basic
"""
This script runs error-prone on all the benchmarks.
Fill in the correct values for the macros at
the top of the file before executing.

The first step is to get Error Prone running on your build, as documented here. Then, you need to get the NullAway jar on the annotation processor path for the Javac invocations where Error Prone is running. Finally, you need to pass the appropriate compiler arguments to configure NullAway (at the least, the -XepOpt:NullAway:AnnotatedPackages option).
"""

import os
import shutil
import subprocess
import re
import json
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

os.makedirs(SRC_FOLDER, exist_ok=True)
os.makedirs(COMPILED_CLASSES_FOLDER, exist_ok=True)
os.makedirs(RESULTS_FOLDER, exist_ok=True)
os.makedirs(DATASETS_FOLDER, exist_ok=True)
os.makedirs(DATASETS_CACHE_FOLDER, exist_ok=True)

# Remove refactored datasets if exists, then recreate folder
shutil.rmtree(DATASETS_REFACTORED_FOLDER, ignore_errors=True)
os.makedirs(DATASETS_REFACTORED_FOLDER, exist_ok=True)

# Download datasets if they don't already exist
if len(os.listdir(DATASETS_CACHE_FOLDER)) == 0:
    print("Downloading Datasets...")
    os.mkdir(DATASETS_CACHE_FOLDER)

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


# Loop through the benchmarks
print("Starting Benchmarks...")


# Benchmarking Stage One:   Running NullAway on unmodified NJR-1 Dataset
# Benchmarking Stage Two:   Refactoring NJR-1 Dataset with VGRTool
# Benchmarking Stage Three: Running NullAway on refactored NJR-1 Dataset
# Benchmarking Stage Four:  Analyze and save data
benchmark_results: dict[str, dict[str, int]] = {}


def stage_one():
    print("Benchmarking Stage One: Running NullAway on unmodified NJR-1 Dataset")
    results = run_benchmark(DATASETS_CACHE_FOLDER)
    for benchmark, value in results.items():
        benchmark_results[benchmark]["initial_error_count"] = value


def stage_two():
    print("Benchmarking Stage Two: Refactoring NJR-1 Dataset with VGRTool")
    res = os.system(f"cp -r {DATASETS_CACHE_FOLDER} {DATASETS_REFACTORED_FOLDER}")
    if res != 0:
        print(
            f"Copying datasets for refactoring failed with exit code {res}. Exiting Program"
        )
        sys.exit(1)
    res = os.system("./gradlew run {DATASETS_REFACTORED_FOLDER} all")
    if res != 0:
        print(f"Running VGRTool failed with exit code {res}. Exiting Program")
        sys.exit(1)


def stage_three():
    print("Benchmarking Stage Three: Running NullAway on refactored NJR-1 Dataset")
    results = run_benchmark(DATASETS_REFACTORED_FOLDER)
    for benchmark, value in results.items():
        benchmark_results[benchmark]["refactored_error_count"] = value


def stage_four():
    # 1. Convert to DataFrame
    df = pd.DataFrame.from_dict(benchmark_results, orient="index")

    # 2. Calculate average difference (whole number and percent)
    df["error_reduction"] = df["col1"] - df["col2"]
    avg_diff = df["error_reduction"].mean()

    df["error_reduction_percent"] = ((df["col1"] - df["col2"]) / df["col1"]) * 100
    df["percent_reduction"].replace([float("inf"), -float("inf")], pd.NA)
    avg_percent_reduction = df["percent_reduction"].dropna().mean()

    # 3. Save to CSV
    df.to_csv(f"{SUMMARY_CSV}")

    print("SUMMARY OF RESULTS:")
    print(f"AVERAGE ERROR REDUCTION: {avg_diff}")
    print(f"AVERAGE ERROR REDUCTION (PERCENT): {avg_percent_reduction}")
    print(
        f"BENCHMARKS WITH LARGEST PERCENT REDUCTION): {df.sort_values(by='percent_reduction', ascending=False).head(10)}"
    )


def run_benchmark(path: str):
    errors: dict[str, int] = {}
    for benchmark in os.listdir(path):
        print(f"Benchmarking {os.path.basename(benchmark)}...")

        # skip non-directories
        if not os.path.isdir(f"{DATASETS_FOLDER}/{benchmark}"):
            continue

        # Get a list of Java source code files.
        benchmark_path = os.path.join(DATASETS_FOLDER, benchmark)
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
        lib_folder = f"{DATASETS_FOLDER}/{benchmark}/lib"

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
            ":".join(
                [
                    f"{BENCHMARKING_FOLDER}/tools/errorprone/error_prone_core-2.38.0-with-dependencies.jar",
                    f"{BENCHMARKING_FOLDER}/tools/errorprone/dataflow-errorprone-3.49.3-eisop1.jar",
                    f"{BENCHMARKING_FOLDER}/tools/errorprone/jFormatString-3.0.0.jar",
                    f"{BENCHMARKING_FOLDER}/tools/nullaway/nullaway-0.12.7.jar",
                    f"{BENCHMARKING_FOLDER}/tools/nullaway/dataflow-nullaway-3.49.5.jar",
                    f"{BENCHMARKING_FOLDER}/tools/nullaway/checker-qual-3.49.2.jar",
                ]
            ),
            f"-Xplugin:ErrorProne {annotated_pkgs_arg} -XepDisableAllChecks -Xep:NullAway:ERROR",
            "-Xmaxerrs",
            "0",
            "-Xmaxwarns",
            "0",
            f"@{src_file}",
        ]

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
        shutil.rmtree(COMPILED_CLASSES_FOLDER)
    return errors


if len(sys.argv) == 1:
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
        avg_percent_reduction = df["percent_reduction"].dropna().mean()
        print("SUMMARY OF LAST RUN: ")
        print(f"AVERAGE ERROR REDUCTION: {avg_diff}")
        print(f"AVERAGE ERROR REDUCTION (PERCENT): {avg_percent_reduction}")
        print(
            f"BENCHMARKS WITH LARGEST PERCENT REDUCTION): {df.sort_values(by='percent_reduction', ascending=False).head(10)}"
        )
    else:
        print("Error getting summary of last run: File {SUMMARY_CSV} does not exist.")
else:
    print("Usage: python process_data.py")
    print("Arguments:")
    print(
        "\tsummary\tSummarizes results from last benchmark rather than re-running the benchmark (optional)"
    )
