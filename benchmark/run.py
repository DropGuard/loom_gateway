import subprocess
import sys
from pathlib import Path

BENCHMARK_DIR = Path(__file__).parent
PROJECT_ROOT = BENCHMARK_DIR.parent
LOOM_DIR = BENCHMARK_DIR / "gateway-loom"
SCG_DIR = BENCHMARK_DIR / "gateway-scg"
RESULTS_DIR = BENCHMARK_DIR / "results"

def run(cmd, cwd=None):
    print(f"\n>>> {cmd}")
    result = subprocess.run(cmd, shell=True, cwd=cwd)
    if result.returncode != 0:
        print(f"Command failed with exit code {result.returncode}")
        sys.exit(1)

def bench(profile):
    """Run a benchmark profile. docker compose --abort-on-container-exit may return
    non-zero even on success, so we verify by checking the JSON output file instead."""
    subprocess.run(
        f"docker compose --profile {profile} up --build --abort-on-container-exit",
        shell=True, cwd=BENCHMARK_DIR,
    )
    subprocess.run(
        f"docker compose --profile {profile} down --remove-orphans",
        shell=True, cwd=BENCHMARK_DIR,
    )
    result_file = RESULTS_DIR / f"{profile}.json"
    if not result_file.exists():
        print(f"Benchmark failed: {result_file} not generated")
        sys.exit(1)
    print(f"Result: {result_file}")

def main():
    print("=== Installing Loom Gateway library (java-gateway) to local repo ===")
    # gateway-loom consumes java-gateway as a Maven dependency, so the library
    # must be installed before the benchmark app can resolve it.
    run("mvn clean install -DskipTests -q", cwd=PROJECT_ROOT)

    print("=== Building Loom Gateway benchmark app ===")
    run("mvn clean package -DskipTests -q", cwd=LOOM_DIR)

    print("=== Building Spring Cloud Gateway ===")
    run("mvn package -DskipTests -q", cwd=SCG_DIR)

    print("=== Running Loom Gateway benchmark ===")
    bench("loom")

    print("=== Running Spring Cloud Gateway benchmark ===")
    bench("scg")

    print("=== Generating comparison ===")
    run(f"{sys.executable} compare.py", cwd=BENCHMARK_DIR)

if __name__ == "__main__":
    main()
