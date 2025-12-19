#!/bin/bash
# Create merged CSV files showing before/after stats for all benchmarks

cd "$(dirname "$0")"

echo "Creating merged results with before/after stats..."
echo ""

python3 merge_results.py --all

echo ""
echo "✅ Done! Merged CSV files are in the results/ folder"
echo ""
echo "Opening results folder..."
open ./results

