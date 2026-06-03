import json
import sys
from pathlib import Path

RESULTS_DIR = Path(__file__).parent / "results"

def load(name):
    path = RESULTS_DIR / f"{name}.json"
    if not path.exists():
        print(f"Missing: {path}")
        sys.exit(1)
    return json.loads(path.read_text())

def fmt_ms(val):
    return f"{val:.2f}ms"

def fmt_rate(val):
    return f"{val * 100:.2f}%"

def fmt_count(val):
    return f"{val:.0f}"

def main():
    loom = load("loom")
    scg = load("scg")

    rows = [
        ("open avg",    fmt_ms(loom["open_latency"]["avg"]),    fmt_ms(scg["open_latency"]["avg"])),
        ("open med",    fmt_ms(loom["open_latency"]["med"]),    fmt_ms(scg["open_latency"]["med"])),
        ("open P90",    fmt_ms(loom["open_latency"]["p(90)"]),  fmt_ms(scg["open_latency"]["p(90)"])),
        ("open P95",    fmt_ms(loom["open_latency"]["p(95)"]),  fmt_ms(scg["open_latency"]["p(95)"])),
        ("auth avg",    fmt_ms(loom["auth_latency"]["avg"]),    fmt_ms(scg["auth_latency"]["avg"])),
        ("auth med",    fmt_ms(loom["auth_latency"]["med"]),    fmt_ms(scg["auth_latency"]["med"])),
        ("auth P90",    fmt_ms(loom["auth_latency"]["p(90)"]),  fmt_ms(scg["auth_latency"]["p(90)"])),
        ("auth P95",    fmt_ms(loom["auth_latency"]["p(95)"]),  fmt_ms(scg["auth_latency"]["p(95)"])),
        ("RPS",         fmt_count(loom["http_reqs"]["rate"]),    fmt_count(scg["http_reqs"]["rate"])),
        ("open errors", fmt_rate(loom["open_errors"]["rate"]),   fmt_rate(scg["open_errors"]["rate"])),
        ("auth errors", fmt_rate(loom["auth_errors"]["rate"]),   fmt_rate(scg["auth_errors"]["rate"])),
    ]

    lines = [
        "# Benchmark: Loom Gateway vs Spring Cloud Gateway",
        "",
        "| Metric | Loom Gateway | Spring Cloud Gateway |",
        "|--------|-------------|---------------------|",
    ]
    for metric, loom_val, scg_val in rows:
        lines.append(f"| {metric} | {loom_val} | {scg_val} |")

    lines.append("")
    lines.append(f"- Loom Gateway name: `{loom['gateway']}`")
    lines.append(f"- SCG name: `{scg['gateway']}`")

    output = RESULTS_DIR / "compare.md"
    output.write_text("\n".join(lines) + "\n")
    print(f"Written to {output}")
    print()
    print("\n".join(lines))

if __name__ == "__main__":
    main()
