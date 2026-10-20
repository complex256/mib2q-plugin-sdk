#!/usr/bin/env python3
"""Generates the files mibsdk-dev pushes to the unit (run on the Mac; the unit has no sed/awk).

  gen_bundles.py generate --stock <stock.properties> --tracer-active <true|false> --out <file>
  gen_bundles.py sdkprops --tracer-active <true|false> --out <file>

Every key in bundles.properties other than the two special ones is a bundle definition to LSD, so
`generate` touches exactly: the serviceDelegateConfigurator line, the Bundle.Startup.Autostart value, and
the HMICar= value, and one appended `MibSdk=` line. Everything else is copied byte for byte. Its output is a fixed point.
"""
import argparse
import re
import sys

CONFIGURATOR_KEY = b"de.audi.tghu.serviceDelegateConfigurator"
CONFIGURATOR_VALUE = b"mibsdk.adapter.hqc.ChainingConfigurator"
AUTOSTART_KEY = b"Bundle.Startup.Autostart"
BUNDLE_NAME = b"MibSdk"
BUNDLE_LINE = b"MibSdk=mibsdk.adapter.hqc.SdkBundleActivator"
TRACER = b"DSITracer"
HMICAR_KEY = b"HMICar"
HMICAR_STOCK = b"de.audi.tghu.car.hmi.Activator"
HMICAR_VALUE = b"mibsdk.adapter.hqc.ui.SdkCarActivator"

_CFG_RE = re.compile(rb"^(#+[ \t]*)?" + re.escape(CONFIGURATOR_KEY) + rb"[ \t]*=")
_AUTO_RE = re.compile(rb"^(" + re.escape(AUTOSTART_KEY) + rb"[ \t]*=[ \t]*)(.*?)$")
_HMICAR_RE = re.compile(rb"^" + re.escape(HMICAR_KEY) + rb"[ \t]*=(.*)$")
_BUNDLE_RE = re.compile(rb"^" + re.escape(BUNDLE_NAME) + rb"[ \t]*=")


def _split_eol(line):
    if line.endswith(b"\r\n"):
        return line[:-2], b"\r\n"
    if line.endswith(b"\n"):
        return line[:-1], b"\n"
    return line, b""


def generate(stock, tracer_active):
    lines = stock.splitlines(keepends=True)
    cfg_idx = [i for i, l in enumerate(lines) if _CFG_RE.match(l)]
    active = [i for i in cfg_idx if not lines[i].startswith(b"#")]
    if not cfg_idx:
        raise SystemExit("gen_bundles: no serviceDelegateConfigurator line in the stock file")
    if len(active) > 1:
        raise SystemExit("gen_bundles: %d active serviceDelegateConfigurator lines in the stock file (the last would"
                         " win); refusing to guess" % len(active))
    target = active[0] if active else cfg_idx[0]
    body, eol = _split_eol(lines[target])
    lines[target] = CONFIGURATOR_KEY + b"=" + CONFIGURATOR_VALUE + eol

    auto = [i for i, l in enumerate(lines) if _AUTO_RE.match(_split_eol(l)[0])]
    if len(auto) != 1:
        raise SystemExit("gen_bundles: expected exactly one Bundle.Startup.Autostart line, found %d" % len(auto))
    body, eol = _split_eol(lines[auto[0]])
    m = _AUTO_RE.match(body)
    rest = [x.strip() for x in m.group(2).split(b",") if x.strip() and x.strip() not in (TRACER, BUNDLE_NAME)]
    head = [TRACER] if tracer_active else []
    lines[auto[0]] = m.group(1) + b",".join(head + [BUNDLE_NAME] + rest) + eol

    hmi = [(i, _HMICAR_RE.match(_split_eol(l)[0])) for i, l in enumerate(lines)]
    hmi = [(i, m) for i, m in hmi if m]
    if len(hmi) != 1:
        raise SystemExit("gen_bundles: expected exactly one HMICar= line, found %d" % len(hmi))
    i, m = hmi[0]
    # Exact bytes; the SDK's own value is accepted too, so generate() is a fixed point.
    if m.group(0) not in (HMICAR_KEY + b"=" + HMICAR_STOCK, HMICAR_KEY + b"=" + HMICAR_VALUE):
        raise SystemExit("gen_bundles: HMICar is not stock (%s); refusing" % m.group(1).decode("latin-1").strip())
    lines[i] = HMICAR_KEY + b"=" + HMICAR_VALUE + _split_eol(lines[i])[1]

    if not any(_BUNDLE_RE.match(l) for l in lines):
        eol = b"\r\n" if lines and lines[-1].endswith(b"\r\n") else b"\n"
        if lines and not lines[-1].endswith(b"\n"):
            lines[-1] += eol
        lines.append(BUNDLE_LINE + eol)
    return b"".join(lines)


def sdkprops(tracer_active):
    out = b""
    if tracer_active:
        out += b"chain.configurator=de.audi.tghu.dsitrace.TraceConfigurator\n"
    return out + b"hook.services=org.dsi.ifc.carparkingsystem.DSICarParkingSystem\nui.enabled=true\n"


def _bool(v):
    if v not in ("true", "false"):
        raise argparse.ArgumentTypeError("expected true or false")
    return v == "true"


def main(argv=None):
    ap = argparse.ArgumentParser(prog="gen_bundles")
    sub = ap.add_subparsers(dest="cmd", required=True)
    g = sub.add_parser("generate")
    g.add_argument("--stock", required=True)
    g.add_argument("--tracer-active", required=True, type=_bool)
    g.add_argument("--out", required=True)
    s = sub.add_parser("sdkprops")
    s.add_argument("--tracer-active", required=True, type=_bool)
    s.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    if a.cmd == "generate":
        with open(a.stock, "rb") as f:
            data = generate(f.read(), a.tracer_active)
    else:
        data = sdkprops(a.tracer_active)
    with open(a.out, "wb") as f:
        f.write(data)


if __name__ == "__main__":
    main(sys.argv[1:])
