#!/usr/bin/env python3
"""Render compact T7 SVGs from harness CSV using Python standard library only."""
import csv
import math
import sys


def load(path):
    with open(path, newline="") as source:
        return list(csv.DictReader(source))


def line_plot(rows, path, title, xfield, series, y_label):
    width, height = 1000, 420
    left, right, top, bottom = 76, 22, 44, 54
    plot_w, plot_h = width - left - right, height - top - bottom
    xs = [float(row[xfield]) for row in rows]
    ys = [float(row[key]) for row in rows for _, key, _ in series]
    if not xs or not ys:
        raise ValueError("empty T7 CSV")
    xmin, xmax = min(xs), max(xs)
    ymin, ymax = min(ys), max(ys)
    span = max(1e-9, ymax - ymin)
    ymin -= span * 0.08
    ymax += span * 0.08
    x = lambda value: left + (value - xmin) / max(1e-9, xmax - xmin) * plot_w
    y = lambda value: top + (ymax - value) / (ymax - ymin) * plot_h
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
           '<rect width="100%" height="100%" fill="white"/>',
           f'<text x="{left}" y="26" font-family="sans-serif" font-size="18">{title}</text>']
    for i in range(6):
        value = ymin + (ymax - ymin) * i / 5
        yy = y(value)
        out.append(f'<path d="M {left} {yy:.2f} H {width-right}" stroke="#ddd"/>')
        out.append(f'<text x="{left-8}" y="{yy+4:.2f}" text-anchor="end" font-family="sans-serif" font-size="11">{value:.3g}</text>')
    out.append(f'<path d="M {left} {top} V {height-bottom} H {width-right}" fill="none" stroke="#333"/>')
    for label, key, color in series:
        points = []
        for row in rows:
            xv, yv = float(row[xfield]), float(row[key])
            if math.isfinite(xv) and math.isfinite(yv):
                points.append(f'{x(xv):.2f},{y(yv):.2f}')
        out.append(f'<polyline points="{" ".join(points)}" fill="none" stroke="{color}" stroke-width="1.4"/>')
    out.append(f'<text x="{left+plot_w/2:.1f}" y="{height-14}" text-anchor="middle" font-family="sans-serif" font-size="12">elapsed source time (s)</text>')
    out.append(f'<text transform="translate(18 {top+plot_h/2:.1f}) rotate(-90)" text-anchor="middle" font-family="sans-serif" font-size="12">{y_label}</text>')
    for i, (label, _, color) in enumerate(series):
        xx = left + i * 210
        out.append(f'<path d="M {xx} 40 h 22" stroke="{color}" stroke-width="2"/><text x="{xx+28}" y="44" font-family="sans-serif" font-size="12">{label}</text>')
    out.append('</svg>')
    with open(path, "w") as target:
        target.write("\n".join(out))


if len(sys.argv) != 3:
    raise SystemExit("usage: plot_t7_evidence.py INPUT.csv OUTPUT_PREFIX")
rows = load(sys.argv[1])
line_plot(rows, sys.argv[2] + "-position.svg", "T7 position error vs source time", "elapsed_s",
          [("transport − truth (beats)", "position_error_beat", "#b3261e"),
           ("matched endpoint − truth at feature frame", "match_truth_error_at_observation", "#1769aa")], "quarter beats")
line_plot(rows, sys.argv[2] + "-speed.svg", "T7 base vs effective cursor speed", "elapsed_s",
          [("base BPM", "base_bpm", "#1769aa"), ("effective cursor BPM", "effective_cursor_bpm", "#b3261e")], "BPM")
line_plot(rows, sys.argv[2] + "-features.svg", "T7 feature-center timestamps", "elapsed_s",
          [("latest live feature center time", "feature_center_s", "#1769aa"),
           ("latest processed PCM time", "processed_time_s", "#b3261e")], "source seconds")
