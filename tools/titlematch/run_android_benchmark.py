"""Run only the isolated benchmark package and reject stale device reports."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import uuid

from fetch_resources import ROOT


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    parser.add_argument("--output",type=Path,default=ROOT/".cache/device-v2.json")
    args=parser.parse_args()
    adb=["adb"]+(["-s",args.serial] if args.serial else [])
    package="com.phnem.vetro.titlematchbench"
    apk=ROOT/"android-benchmark/build/outputs/apk/debug/VetroTitleMatchBenchmark-debug.apk"
    manifest=json.loads((ROOT/".cache/benchmark-assets/titlematch/v1/manifest.json").read_text())
    subprocess.run(adb+["install","-r",str(apk)],check=True)
    subprocess.run(adb+["shell","am","force-stop",package],check=True)
    run_id=uuid.uuid4().hex
    subprocess.run(adb+["shell","input","keyevent","KEYCODE_WAKEUP"],check=True)
    subprocess.run(adb+["shell","am","start","-W","-n",package+"/.MainActivity","--es","titlematch_run_id",run_id],check=True)
    deadline=time.monotonic()+120
    while time.monotonic()<deadline:
        result=subprocess.run(adb+["shell","run-as",package,"cat","files/benchmark.json"],capture_output=True)
        if result.returncode==0:
            try:
                report=json.loads(result.stdout.decode())
            except (ValueError,UnicodeDecodeError):
                time.sleep(1)
                continue
            if report.get("run_id")==run_id:
                args.output.write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8",newline="\n")
                if not report.get("passed") or report.get("model_version")!=manifest["model_version"]:
                    raise SystemExit("Current device benchmark failed: "+json.dumps(report))
                print(json.dumps(report,indent=2))
                return
        time.sleep(1)
    raise SystemExit("No fresh benchmark report; old results were not accepted")


if __name__=="__main__":main()
