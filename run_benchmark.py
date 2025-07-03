# pyright: basic
import os
import tempfile
import sys
import shutil
import subprocess
import pandas as pd
from concurrent.futures import ThreadPoolExecutor, as_completed

# NOTE: Set these constants if not using defaults
BENCHMARK_DIR = "./benchmarking"
OUTPUT_DIR = f"{BENCHMARK_DIR}/outputs"  # Directory for outputs
OUTPUT_LOGS_SUBDIR = f"{OUTPUT_DIR}/logs"  # Subdirectory for errorprone / VGRTool logs
OUTPUT_CSV = f"{OUTPUT_DIR}/benchmarks.csv"  # CSV file for benchmarking data

REFACTORING_MODULE = "All"  # VGRTool refactoring module to use

DATASETS_DIR = f"{BENCHMARK_DIR}/datasets"  # Location of datasets to benchmark
COMPILATION_DIR = f"{BENCHMARK_DIR}/ep_classes"  # Where compiled classes will be stored
ANNOTATION_PROCESSOR_DIR = f"{BENCHMARK_DIR}/annotation-processors/"  # Where we store the annotation processor jars
TIMEOUT = 300  # Maxmimum time to run the annotaters on a project, in seconds
SRC_FILE = f"{BENCHMARK_DIR}/ep_srcs.txt"

# NOTE: There should be no need to change anything beyond this line
PRE_OUTPUT_LOGS_SUBDIR = f"{OUTPUT_LOGS_SUBDIR}/pre-refactor"  # Subdirectory for errorprone logs before refactoring
REFACTORING_OUTPUT_LOGS_SUBDIR = (
    f"{OUTPUT_LOGS_SUBDIR}/refactor"  # Subdirectory for refactoring logs
)
POST_OUTPUT_LOGS_SUBDIR = f"{OUTPUT_LOGS_SUBDIR}/post-refactor"  # Subdirectory for errorprone logs after refactoring

USAGE_STR = "Usage: python run_nullway.py <initialize|finalize|test>"

ERRORPRONE_JARS_LIST = [
    "checker-qual-3.49.5.jar",
    "dataflow-errorprone-3.49.3-eisop1.jar",
    "dataflow-nullaway-3.49.2.jar",
    "error_prone_core-2.39.0-with-dependencies.jar",
    "jFormatString-3.0.0.jar",
    "nullaway-0.12.7.jar",
]
ERRORPRONE_JARS = ":".join(
    [f"{ANNOTATION_PROCESSOR_DIR}/{jar}" for jar in ERRORPRONE_JARS_LIST]
)

ERRORPRONE_EXPORTS_LIST = [
    "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
    "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
    "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
]

ERRORPRONE_EXPORTS = ["-J" + flag for flag in ERRORPRONE_EXPORTS_LIST]

ERRORPRONE_PLUGIN_ARGS = (
    "-Xplugin:ErrorProne "
    "-XepDisableAllChecks "
    "-Xep:NullAway:ERROR "
    "-Xmaxerrs "
    "-XepOpt:NullAway:AnnotatedPackages=a,achiever,actionmenu,agapi,agent,agentes,ai,algorithm,ar,assignments,attributes,atuador,b,backend,banksystem,basic,be,behavior,benchmarks,book,br,by,bytecast,c,careercup,Centralizador,ch,ch2,chess,clarion,client,cn,com,coho,com,commands,common,competition,concurrent,constants,controllers,coursescheduleapp,crackinginterview,cscie97,cz,d,daoo,darwin,darwInvest,data,datastructure,dbathon,de,ded,dk,dnd,domain,drivenow,DrunkardGame,ec,edu,ee,effective,entity,erilex,es,eu,event,experiments,expression,farom,fr,frame,framework,fri,frs,fw,fws,generativemodel,glass,gramatica,grammaticalbehaviors,grammaticalbehaviorsNoAstar,grinder,groupsignature,gui,GUI,hdfs,hep,hobo,hugo,ikrs,imageCompression,in,io,istruzioni,jasl,jatrace,java,java5,javax,junit,kademlia,karlney,kth,laazotea,learn,leetcode,lib,littlemangame,logfilegen,logica,M1,m68k,main,malictus,manager,martin,mi,minewebsocket,minidraw,model,modes,monitor,mp1401,myastro,name,net,neworder,nextgen,nio,nl,normchan,nui,nz,org,ori,parse,patterns,permlab,permlib,ping,pl,price,private,protocols,publishers,query,Questing,question2,reso,risiko,rivercityransom,root,rpg,ru,rummyj,sandbox,scribe,Sentiens,SevenZip,sg,signature,simulation,sitsiplaseeraus,sjm,soc,solver,spatialindex,ssamot,startrekj,stv6,sudoku,symbolicRegression,terminal,test,threads,tivoo,tp1,tradable,ubc,ubs,uk,units,util,utility,uy,v,view,vikram,visibility,vsdk,websocket,weibo4j,wikipedia,wjd,wox,xpathParser,xscript,xx,yeah,zz"
)

DATASETS = os.listdir(DATASETS_DIR)


if len(DATASETS) == 0:
    print("Error: No datasets to benchmark")
    sys.exit(1)


# Create output folders
for dir in [
    BENCHMARK_DIR,
    OUTPUT_LOGS_SUBDIR,
    PRE_OUTPUT_LOGS_SUBDIR,
    REFACTORING_OUTPUT_LOGS_SUBDIR,
    POST_OUTPUT_LOGS_SUBDIR,
    OUTPUT_DIR,
    DATASETS_DIR,
    COMPILATION_DIR,
    ANNOTATION_PROCESSOR_DIR,
]:
    os.makedirs(f"{dir}", exist_ok=True)


# Benchmark a single dataset
def run_benchmark(benchmark: str, logpath: str):
    print(f"Benchmarking: {benchmark}")
    benchmark_path = os.path.join(DATASETS_DIR, benchmark)
    if not os.path.isdir(benchmark_path):
        return {"name": benchmark, "value": -999}

    os.makedirs(COMPILATION_DIR, exist_ok=True)

    # Find source files
    src_path = os.path.join(benchmark_path, "src")
    find_cmd = ["find", src_path, "-name", "*.java"]

    with tempfile.NamedTemporaryFile(
        mode="w", delete=False, suffix=".txt"
    ) as temp_src_file:
        temp_src_filename = temp_src_file.name
        try:
            subprocess.run(
                find_cmd,
                stdout=open(temp_src_filename, "w"),
                stderr=subprocess.PIPE,
                text=True,
            )
        except subprocess.SubprocessError as e:
            with open(f"{logpath}/{benchmark}.txt", "w") as out_file:
                out_file.write(f"Error finding sources for {benchmark}: {str(e)}")
            return {"name": benchmark, "value": -999}

    lib_folder = os.path.join(benchmark_path, "lib")

    # Build javac command
    javac_cmd = [
        "javac",
        "-XDcompilePolicy=simple",
        "-d",
        COMPILATION_DIR,
        "-cp",
        lib_folder,
        "-processorpath",
        ERRORPRONE_JARS,
        *ERRORPRONE_EXPORTS,
        "--should-stop=ifError=FLOW",
        f"-Xplugin:ErrorProne '{ERRORPRONE_PLUGIN_ARGS}'",
        f"@{temp_src_filename}",
    ]
    result = subprocess.run(
        javac_cmd,
        stderr=subprocess.PIPE,
        stdout=subprocess.DEVNULL,
        text=True,
        timeout=TIMEOUT,
    ).stderr

    # Write stderr output to file
    with open(f"{logpath}/{benchmark}.txt", "w") as out_file:
        _ = out_file.write(result)
        _ = out_file.write("\n FIND_CMD \n ")
        _ = out_file.write(" ".join(find_cmd))
        _ = out_file.write("\n JAVAC_CMD \n ")
        _ = out_file.write(" ".join(javac_cmd))

    warningCount = result.count("error: [NullAway]")
    shutil.rmtree(COMPILATION_DIR, ignore_errors=True)
    return {"name": benchmark, "value": warningCount}


# Benchmark all datasets
def run_benchmarks(logpath: str):
    with ThreadPoolExecutor(max_workers=os.cpu_count()) as executor:
        futures = [
            executor.submit(run_benchmark, dataset, logpath) for dataset in DATASETS
        ]
        results = [f.result() for f in as_completed(futures)]
    return pd.DataFrame(results)


# NOTE: Benchmarking Stage 1
# = Empties/Creates result directory
# - Runs NullAway on all benchmarks
# - Stores results in CSV
def initialize_benchmark():
    print("Initializing Benchmark")
    data = run_benchmarks(PRE_OUTPUT_LOGS_SUBDIR)
    data.rename(columns={"value": "pre_value"}, inplace=True)
    data.to_csv(OUTPUT_CSV, index=False)


# NOTE: Benchmarking Stage 2
# - Runs VGRTool on all benchmarks
def refactor_dataset(dataset: str):
    print(f"Refactoring {dataset}...")
    output_file_path = f"{REFACTORING_OUTPUT_LOGS_SUBDIR}/{dataset}.txt"
    with open(output_file_path, "w") as output_file:
        cmd = [
            "java",
            "-jar",
            "build/libs/VGRTool-Full-1.0.jar",
            f"{DATASETS_DIR}/{dataset}",
            f"{REFACTORING_MODULE}",
        ]
        _ = subprocess.run(
            cmd,
            stderr=output_file,
            stdout=output_file,
            text=True,
            timeout=TIMEOUT,
        )
        output_file.write("\n REFACTORING COMMAND \n ")
        output_file.write(" ".join(cmd))
    print(f"Finished refactoring {dataset}\n")


def refactor():
    with ThreadPoolExecutor(max_workers=os.cpu_count()) as executor:
        futures = [executor.submit(refactor_dataset, dataset) for dataset in DATASETS]
        _ = [f.result() for f in as_completed(futures)]


# NOTE: Benchmarking Stage 3
# - Reruns NullAway on all benchmarks
# - Appends results to CSV
# - Calculates the average warning decrease
# - Restores original datasets
def finalize_benchmark():
    print("Finalizing Benchmark")
    pre_data: pd.DataFrame = pd.read_csv(OUTPUT_CSV).drop(
        columns=["post_value"], errors="ignore"
    )
    post_data: pd.DataFrame = run_benchmarks(POST_OUTPUT_LOGS_SUBDIR)
    post_data.rename(columns={"value": "post_value"}, inplace=True)
    benchmark_data: pd.DataFrame = pd.merge(pre_data, post_data, on="name", how="inner")
    benchmark_data.to_csv(OUTPUT_CSV, index=False)


def summarize():
    df = pd.read_csv(OUTPUT_CSV)

    # Calculate the difference
    df["difference"] = df["pre_value"] - df["post_value"]

    # Calculate average difference
    average_diff = df["difference"].mean()

    # Get top 5 rows with greatest differences
    top_5 = df.nlargest(5, "difference")[["name", "difference"]]

    # Get bottom 5 rows with smallest differences
    bottom_5 = df.nsmallest(5, "difference")[["name", "difference"]]

    print(f"Average Decrease: {average_diff}")
    print("Largest Decrease:\n", top_5)
    print()
    print("Smallest Decrease:\n", bottom_5)
    print()


def reset():
    os.system("git restore benchmarking/datasets/")
    os.system("./gradlew clean; ./gradlew fullJar")


if len(sys.argv) < 2:
    reset()
    initialize_benchmark()
    refactor()
    finalize_benchmark()
    summarize()
else:
    match sys.argv[1]:
        case "initialize":
            initialize_benchmark()
        case "finalize":
            finalize_benchmark()
        case "refactor":
            refactor()
        case "summarize":
            summarize()
        case "skip-init":
            refactor()
            finalize_benchmark()
            summarize()
        case "reset":
            reset()
        case "test":
            print(PRE_OUTPUT_LOGS_SUBDIR)
            print(REFACTORING_OUTPUT_LOGS_SUBDIR)
            print(POST_OUTPUT_LOGS_SUBDIR)
        case _:
            print(USAGE_STR)
