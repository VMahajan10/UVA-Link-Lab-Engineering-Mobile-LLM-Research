#!/usr/bin/env python3
"""
Merge benchmark query results and battery metrics into a single CSV
showing stats before and after each query execution.
"""

import csv
import sys
import os
from datetime import datetime
from pathlib import Path

def parse_timestamp(ts_str):
    """Parse timestamp string to datetime object."""
    try:
        return datetime.strptime(ts_str, "%Y-%m-%d %H:%M:%S.%f")
    except:
        try:
            return datetime.strptime(ts_str, "%Y-%m-%d %H:%M:%S")
        except:
            return None

def load_queries(query_file):
    """Load query results from CSV."""
    queries = []
    with open(query_file, 'r') as f:
        reader = csv.DictReader(f)
        for row in reader:
            row['timestamp'] = parse_timestamp(row['timestamp'])
            queries.append(row)
    return queries

def load_battery_metrics(battery_file):
    """Load battery metrics from CSV."""
    metrics = []
    with open(battery_file, 'r') as f:
        reader = csv.DictReader(f)
        for row in reader:
            row['timestamp'] = parse_timestamp(row['timestamp'])
            metrics.append(row)
    return metrics

def find_closest_metric(target_time, metrics, before=True):
    """Find closest battery metric before or after target time."""
    if not metrics:
        return None
    
    closest = None
    min_diff = float('inf')
    
    for metric in metrics:
        if metric['timestamp'] is None:
            continue
        
        diff = (target_time - metric['timestamp']).total_seconds()
        
        if before and diff > 0:  # Before query
            if abs(diff) < min_diff:
                min_diff = abs(diff)
                closest = metric
        elif not before and diff < 0:  # After query
            if abs(diff) < min_diff:
                min_diff = abs(diff)
                closest = metric
    
    return closest

def merge_results(query_file, battery_file, output_file):
    """Merge query results and battery metrics."""
    print(f"Loading queries from: {query_file}")
    queries = load_queries(query_file)
    print(f"  Found {len(queries)} queries")
    
    print(f"Loading battery metrics from: {battery_file}")
    battery_metrics = load_battery_metrics(battery_file)
    print(f"  Found {len(battery_metrics)} battery metrics")
    
    # Sort by timestamp
    queries.sort(key=lambda x: x['timestamp'] if x['timestamp'] else datetime.min)
    battery_metrics.sort(key=lambda x: x['timestamp'] if x['timestamp'] else datetime.min)
    
    # Create merged rows
    merged_rows = []
    
    for i, query in enumerate(queries):
        if query['timestamp'] is None:
            continue
        
        # Find battery metrics before and after this query
        before_metric = find_closest_metric(query['timestamp'], battery_metrics, before=True)
        after_metric = find_closest_metric(query['timestamp'], battery_metrics, before=False)
        
        # Create merged row
        row = {
            'query_number': i + 1,
            'timestamp': query['timestamp'].strftime("%Y-%m-%d %H:%M:%S.%f")[:-3],
            'query_text': query['queryText'],
            'response_preview': query['responseText'][:100] + '...' if len(query['responseText']) > 100 else query['responseText'],
            'inference_time_ms': query['inferenceTimeMs'],
            'model_name': query['modelName'],
            'quantization': query['quantization'],
            # Battery before query
            'battery_before': before_metric['batteryLevel'] if before_metric else query.get('batteryLevel', 'N/A'),
            'battery_drain_rate_before_per_hour': before_metric.get('batteryDrainRate', 'N/A') if before_metric else 'N/A',  # Projected rate
            'cpu_usage_before': before_metric.get('cpuUsage', 'N/A') if before_metric else 'N/A',
            'memory_usage_before': before_metric.get('memoryUsage', 'N/A') if before_metric else 'N/A',
            'temperature_before': before_metric.get('temperature', 'N/A') if before_metric else 'N/A',
            # Battery after query (from query result)
            'battery_after': query.get('batteryLevel', 'N/A'),
            # Battery after query (from metrics)
            'battery_after_metric': after_metric['batteryLevel'] if after_metric else 'N/A',
            'battery_drain_rate_after_per_hour': after_metric.get('batteryDrainRate', 'N/A') if after_metric else 'N/A',  # Projected rate
            'cpu_usage_after': after_metric.get('cpuUsage', 'N/A') if after_metric else 'N/A',
            'memory_usage_after': after_metric.get('memoryUsage', 'N/A') if after_metric else 'N/A',
            'temperature_after': after_metric.get('temperature', 'N/A') if after_metric else 'N/A',
            # Battery change
            'battery_change': float(query.get('batteryLevel', 0)) - float(before_metric['batteryLevel'] if before_metric and before_metric.get('batteryLevel') else 0),
        }
        
        merged_rows.append(row)
    
    # Write merged CSV
    if not merged_rows:
        print("No data to merge!")
        return
    
    fieldnames = [
        'query_number', 'timestamp', 'query_text', 'response_preview',
        'inference_time_ms', 'model_name', 'quantization',
        'battery_before', 'battery_drain_rate_before_per_hour', 'cpu_usage_before',
        'memory_usage_before', 'temperature_before',
        'battery_after', 'battery_after_metric', 'battery_drain_rate_after_per_hour',
        'cpu_usage_after', 'memory_usage_after', 'temperature_after',
        'battery_change_absolute'
    ]
    
    print(f"\nWriting merged results to: {output_file}")
    with open(output_file, 'w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(merged_rows)
    
    print(f"✅ Created merged CSV with {len(merged_rows)} queries")
    print(f"   File: {output_file}")

def main():
    if len(sys.argv) < 2:
        print("Usage: python3 merge_results.py <query_csv_file> [battery_csv_file] [output_file]")
        print("\nOr run without arguments to process all files in results/ folder")
        sys.exit(1)
    
    if len(sys.argv) == 2 and sys.argv[1] == '--all':
        # Process all files in results folder
        results_dir = Path(__file__).parent / 'results'
        if not results_dir.exists():
            print(f"Results directory not found: {results_dir}")
            sys.exit(1)
        
        # Find all query files
        query_files = sorted(results_dir.glob('llm_benchmark_queries_*.csv'))
        
        for query_file in query_files:
            # Find matching battery file
            timestamp = query_file.stem.replace('llm_benchmark_queries_', '')
            battery_file = results_dir / f'llm_benchmark_battery_{timestamp}.csv'
            
            if battery_file.exists():
                output_file = results_dir / f'llm_benchmark_merged_{timestamp}.csv'
                print(f"\n{'='*60}")
                merge_results(str(query_file), str(battery_file), str(output_file))
            else:
                print(f"⚠️  No matching battery file for {query_file.name}")
        
        print(f"\n{'='*60}")
        print("✅ All files processed!")
        return
    
    query_file = sys.argv[1]
    battery_file = sys.argv[2] if len(sys.argv) > 2 else None
    output_file = sys.argv[3] if len(sys.argv) > 3 else None
    
    if not os.path.exists(query_file):
        print(f"Error: Query file not found: {query_file}")
        sys.exit(1)
    
    # Auto-detect battery file if not provided
    if not battery_file:
        query_path = Path(query_file)
        timestamp = query_path.stem.replace('llm_benchmark_queries_', '')
        battery_file = query_path.parent / f'llm_benchmark_battery_{timestamp}.csv'
        
        if not battery_file.exists():
            print(f"Error: Battery file not found: {battery_file}")
            print("Please specify battery file as second argument")
            sys.exit(1)
    
    # Auto-generate output filename if not provided
    if not output_file:
        query_path = Path(query_file)
        timestamp = query_path.stem.replace('llm_benchmark_queries_', '')
        output_file = query_path.parent / f'llm_benchmark_merged_{timestamp}.csv'
    
    merge_results(query_file, str(battery_file), str(output_file))

if __name__ == '__main__':
    main()

