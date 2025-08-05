# pyright: basic
import os
import re
import shutil
import subprocess
import sys
import pandas as pd

BENCHMARKING_DIR = "./benchmarking"  # Base directory for benchmarking inputs / outputs
DATASETS_DIR = (
    f"{BENCHMARKING_DIR}/datasets"  # Base directory for benchmarking datasets
)
DATASETS_CACHE_DIR = f"{DATASETS_DIR}/cache"  # Cache directory for NJR-1 dataset. Should remain unmodifed
DATASETS_REFACTORED_DIR = (
    f"{DATASETS_DIR}/refactored"  # Directory for datasets that will be modified
)

OUTPUT_DIR = f"{BENCHMARKING_DIR}/results"  # Directory for storing outputs
SRC_DIR = f"{BENCHMARKING_DIR}/sources"  # Directory for storing text files listing source files for each project
COMPILED_CLASSES_DIR = (
    f"{BENCHMARKING_DIR}/compiled_classes"  # Directory for storing compiled_classes
)

JARS_DIR = f"{BENCHMARKING_DIR}/jars"
ERRORPRONE_JAR_DIR = f"{JARS_DIR}/errorprone"
NULLAWAY_JAR_DIR = f"{JARS_DIR}/nullaway"
ANNOTATOR_JAR_DIR = f"{JARS_DIR}/annotator"
PROCESSOR_JARS = ":".join(
    [
        f"{ERRORPRONE_JAR_DIR}/error_prone_core-2.38.0-with-dependencies.jar",
        f"{ERRORPRONE_JAR_DIR}/errorprone/dataflow-errorprone-3.49.3-eisop1.jar",
        f"{ERRORPRONE_JAR_DIR}/errorprone/jFormatString-3.0.0.jar",
        f"{NULLAWAY_JAR_DIR}/nullaway-0.12.7.jar",
        f"{NULLAWAY_JAR_DIR}/dataflow-nullaway-3.49.5.jar",
        f"{NULLAWAY_JAR_DIR}/checker-qual-3.49.2.jar",
        f"{ANNOTATOR_JAR_DIR}/annotator-core-1.3.15.jar",
    ]
)


ANNOTATOR_OUT_DIR = f"{OUTPUT_DIR}/annotator"
SCANNER_CONFIG = f"{ANNOTATOR_OUT_DIR}/scanner.xml"
NULLAWAY_CONFIG = f"{ANNOTATOR_OUT_DIR}/nullaway.xml"
ANNOTATOR_CONFIG = f"{ANNOTATOR_OUT_DIR}/paths.tsv"
ANNOTATOR_JAR = f"{ANNOTATOR_JAR_DIR}/annotator-core-1.3.15.jar"


def initialize():
    print("Initializing benchmarking folders and datasets")
    os.makedirs(SRC_DIR, exist_ok=True)
    os.makedirs(COMPILED_CLASSES_DIR, exist_ok=True)
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    os.makedirs(DATASETS_DIR, exist_ok=True)
    os.makedirs(DATASETS_CACHE_DIR, exist_ok=True)

    # Download datasets if they don't already exist
    if len(os.listdir(DATASETS_CACHE_DIR)) == 0:
        print("Downloading NJR-1 Dataset...")
        # Download NJR-1 Dataset
        res = os.system(
            f"wget https://zenodo.org/records/4632231/files/njr-1_dataset.zip -P {DATASETS_CACHE_DIR}"
        )

        if res != 0:
            print(f"Downloading datasets failed with exit code {res}. Exiting Program")
            sys.exit(1)

        # Extract zip file
        print("Extracting Datasets...")
        res = os.system(
            f"unzip {DATASETS_CACHE_DIR}/njr-1_dataset.zip -d {DATASETS_CACHE_DIR} && rm {DATASETS_CACHE_DIR}/njr-1_dataset.zip && mv {DATASETS_CACHE_DIR}/njr-1_dataset/* {DATASETS_CACHE_DIR} && rmdir {DATASETS_CACHE_DIR}/njr-1_dataset/"
        )
        if res != 0:
            print(
                f"Extracting downloaded datasets failed with exit code {res}. Exiting Program"
            )
            sys.exit(1)

    print("Benchmarking Stage Zero Completed\n")


error_prone_exports = [
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
    "-J--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
    "-J--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
]


def get_source_files(dataset):
    find_srcs_command = [
        "find",
        f"{DATASETS_CACHE_DIR}/{dataset}/src",
        "-name",
        "*.java",
    ]
    src_file = f"{SRC_DIR}/{dataset}.txt"
    with open(src_file, "w") as f:
        _ = subprocess.run(find_srcs_command, stdout=f)
    return src_file


def get_plugin_options(dataset: str):
    dataset_path = f"{DATASETS_CACHE_DIR}/{dataset}"
    find_pkgs_command = (
        f"find {dataset_path}"
        + " -name \"*.java\" -exec awk 'FNR==1 && /^package/ {print $2}' {} + | sed 's/;//' | sort -u | paste -sd,"
    )

    pkgs = subprocess.run(
        find_pkgs_command, shell=True, capture_output=True
    ).stdout.decode("utf-8")

    # Split the annotated packages
    annotated_pkgs = pkgs.strip()
    annotated_pkgs_arg = f"-XepOpt:NullAway:AnnotatedPackages={annotated_pkgs}"

    return f"-Xplugin:ErrorProne \
             -XepDisableAllChecks \
             -Xep:AnnotatorScanner:ERROR \
             -XepOpt:AnnotatorScanner:ConfigPath={SCANNER_CONFIG}  \
             -Xep:NullAway:ERROR \
             -XepOpt:NullAway:SerializeFixMetadata=true \
             -XepOpt:NullAway:FixSerializationConfigPath={NULLAWAY_CONFIG} \
             {annotated_pkgs_arg}"


def get_build_cmd(dataset: str):
    lib_dir = f"{DATASETS_CACHE_DIR}/{dataset}/lib"
    src_file = get_source_files(dataset)
    plugin_options = get_plugin_options(dataset)

    build_cmd: list[str] = ["javac"]
    build_cmd += error_prone_exports
    build_cmd += [
        "-d",
        f"{COMPILED_CLASSES_DIR}",
        "-cp",
        f"{lib_dir}:{ANNOTATOR_JAR}",
        "-XDcompilePolicy=simple",
        "--should-stop=ifError=FLOW",
        "-processorpath",
        f"{PROCESSOR_JARS}",
        f"'{plugin_options}'",
        "-Xmaxerrs",
        "0",
        "-Xmaxwarns",
        "0",
        f"@{src_file}",
    ]
    # print(f"\nBUILD COMMAND FOR DATASET {dataset}:")
    # print(" ".join(build_cmd))
    # print("\n\n")
    # print(build_cmd)
    return build_cmd


def annotate(dataset: str):
    os.makedirs(ANNOTATOR_OUT_DIR, exist_ok=True)
    with open(f"{ANNOTATOR_CONFIG}", "w") as config_file:
        _ = config_file.write(f"{NULLAWAY_CONFIG}/\t{SCANNER_CONFIG}\n")

    # Clear annotator output folder (required for annotator to run)
    shutil.rmtree(ANNOTATOR_OUT_DIR + "/0", ignore_errors=True)

    build_cmd = " ".join(get_build_cmd(dataset))
    cwd = os.getcwd()

    annotate_cmd: list[str] = [
        "java",
        "-jar",
        f"{ANNOTATOR_JAR}",
        # Absolute path of an Empty Directory where all outputs of AnnotatorScanner and NullAway are serialized.
        "-d",
        f"{ANNOTATOR_OUT_DIR}",
        # Command to run Nullaway on target; Should be executable from anywhere
        "-bc",
        f'"cd {cwd} && {build_cmd}"',
        # Path to a TSV file containing value of config paths
        "-cp",
        f"{ANNOTATOR_CONFIG}",
        # Fully qualified name of the @Initializer annotation.
        "-i",
        "com.uber.nullaway.annotations.Initializer",
        # Custom @Nullable annotation.
        "-n",
        "javax.annotation.Nullable",
        # Checker name to be used for the analysis.
        "-cn",
        "NULLAWAY",
        # Max depth to traverse
        "--depth",
        "10",
    ]
    # print(f"\nANNOTATE COMMAND FOR DATASET {dataset}:")
    # print(" ".join(annotate_cmd))
    # print("\n\n")
    result = subprocess.run(annotate_cmd, text=True, capture_output=True)
    # print(f"STDERR:\n{result.stderr}")
    # print(f"STDOUT:\n{result.stdout}")


def get_errors(dataset: str):
    # result = subprocess.run(get_build_cmd(dataset), text=True, capture_output=True)
    # print(f"STDERR:\n{result.stderr}")
    # print(f"STDOUT:\n{result.stdout}")
    build_cmd = " ".join(get_build_cmd(dataset))
    result_file = f"{OUTPUT_DIR}/tmp.txt"
    _ = os.system(f"{build_cmd} &> {result_file}")

    # Read the file and count occurrences of NullAway error
    with open(result_file, "r") as f:
        output = f.read()

    return len(re.findall(r"error: \[NullAway\]", output))


def refactor(dataset: str):
    res = os.system(
        f"./gradlew run --args='{DATASETS_CACHE_DIR}/{dataset} All' &> /dev/null"
    )
    if res != 0:
        print(f"Running VGRTool failed with exit code {res} for dataset {dataset}")
    return


def run():
    results = []

    for dataset in os.listdir(DATASETS_CACHE_DIR):
        print(f"Annotating {dataset}...")
        annotate(dataset)
        old_err_count = get_errors(dataset)
        print(f"Refactoring {dataset}...")
        refactor(dataset)
        new_err_count = get_errors(dataset)
        print(f"Finished benchmarking {dataset}")
        results.append(
            {
                "benchmark": dataset,
                "initial_error_count": old_err_count,
                "refactored_error_count": new_err_count,
            }
        )
    print("Finished running benchmarks")
    summarize(results)
    return


def summarize(results):
    print("Summarizing...:")

    benchmark_results = pd.DataFrame(results)

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

    print("SUMMARY OF RESULTS:")
    print(f"AVERAGE ERROR REDUCTION: {avg_diff}")
    print(f"AVERAGE ERROR REDUCTION (PERCENT): {avg_percent_reduction}")
    print(
        f"BENCHMARKS WITH LARGEST PERCENT REDUCTION): \n{benchmark_results.sort_values(by='error_reduction_percent', ascending=False).head(10)}"
    )
    benchmark_results.to_csv(f"{OUTPUT_DIR}/summary.csv")


run()
