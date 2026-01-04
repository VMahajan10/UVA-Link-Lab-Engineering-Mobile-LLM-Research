#!/usr/bin/env python3
"""
Fix CSV files to remove data that appears after phone reached 5% battery.
This removes unnecessary rows that show higher battery values after termination.
Then recalculates battery_drop values.
"""

import csv
import sys
import os
import io
import re

def is_numeric(value):
    """Check if a value is numeric."""
    if not value or not value.strip():
        return False
    value = value.strip()
    return bool(re.match(r'^-?\d+\.?\d*$', value))

def fix_early_termination(input_file, output_file):
    """Remove rows that appear after phone reached 5% battery, then recalculate battery_drop."""
    print(f"Processing {input_file}...")
    
    # Read the entire file
    with open(input_file, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Use csv.reader to properly handle multi-line fields
    reader = csv.reader(io.StringIO(content))
    all_rows = list(reader)
    
    # Find the header row
    header_idx = None
    battery_after_idx = None
    battery_drop_idx = None
    header = None
    
    for i, row in enumerate(all_rows):
        if len(row) > 0 and 'battery_drop' in str(row) and 'battery_after' in str(row):
            header_idx = i
            header = row
            try:
                battery_after_idx = header.index('battery_after')
                battery_drop_idx = header.index('battery_drop')
                break
            except:
                continue
    
    if header_idx is None:
        print(f"ERROR: Could not find header row with 'battery_drop' in {input_file}")
        return False
    
    # Find the FIRST row where battery_after <= 5%
    first_low_battery_idx = None
    for i in range(header_idx + 1, len(all_rows)):
        row = all_rows[i]
        # Check if row has enough columns to access battery_after (even if length doesn't match header)
        if len(row) > battery_after_idx:
            try:
                battery_after_str = str(row[battery_after_idx]).strip()
                if battery_after_str.replace('.', '').replace('-', '').isdigit():
                    battery_after = float(battery_after_str)
                    # Validate battery is in reasonable range (0-100)
                    if 0 <= battery_after <= 100 and battery_after <= 5:
                        first_low_battery_idx = i
                        break
            except:
                pass
    
    if first_low_battery_idx is None:
        print(f"⚠️  No rows with battery <= 5% found. File may not have early termination.")
        return False
    
    print(f"Found first row with battery <= 5% at row {first_low_battery_idx + 1}")
    
    # Find all rows after the first low battery row that have battery > 5%
    # Also remove rows with invalid battery values (> 100)
    rows_to_remove = set()
    for i in range(first_low_battery_idx + 1, len(all_rows)):
        row = all_rows[i]
        # Check if row has enough columns to access battery_after (even if length doesn't match header)
        if len(row) > battery_after_idx:
            try:
                battery_after_str = str(row[battery_after_idx]).strip()
                if battery_after_str.replace('.', '').replace('-', '').isdigit():
                    battery_after = float(battery_after_str)
                    # Remove rows with battery > 5% OR invalid battery values (> 100)
                    if battery_after > 5 or battery_after > 100:
                        rows_to_remove.add(i)
            except:
                pass
    
    if not rows_to_remove:
        print(f"✅ No rows to remove - file is already correct")
        # Still recalculate battery_drop to be safe
        return recalculate_battery_drop(input_file, output_file, header_idx, battery_after_idx, battery_drop_idx, header)
    
    print(f"Found {len(rows_to_remove)} rows to remove (battery > 5% after first termination point)")
    
    # Create filtered rows list
    filtered_rows = []
    for i in range(len(all_rows)):
        if i <= first_low_battery_idx:
            filtered_rows.append(all_rows[i])
        elif i in rows_to_remove:
            continue
        else:
            row = all_rows[i]
            if len(row) == len(header) and len(row) > battery_after_idx:
                try:
                    battery_after_str = str(row[battery_after_idx]).strip()
                    if battery_after_str.replace('.', '').replace('-', '').isdigit():
                        battery_after = float(battery_after_str)
                        # Only keep rows with valid battery (0-100) and <= 5%
                        if 0 <= battery_after <= 100 and battery_after <= 5:
                            filtered_rows.append(row)
                except:
                    pass
            else:
                # Continuation line - keep if previous row was kept
                if filtered_rows and i - 1 not in rows_to_remove:
                    filtered_rows.append(row)
    
    # Now recalculate battery_drop for the filtered rows
    previous_battery_after = None
    for i in range(header_idx + 1, len(filtered_rows)):
        row = filtered_rows[i]
        if len(row) == len(header) and len(row) > battery_after_idx:
            try:
                battery_after_str = str(row[battery_after_idx]).strip()
                if battery_after_str and is_numeric(battery_after_str):
                    battery_after = float(battery_after_str)
                    # Only process valid battery values (0-100)
                    if 0 <= battery_after <= 100:
                        # Calculate battery drop
                        if previous_battery_after is not None:
                            battery_drop = previous_battery_after - battery_after
                            new_drop_value = str(int(battery_drop)) if battery_drop == int(battery_drop) else str(battery_drop)
                        else:
                            new_drop_value = '0'
                        
                        # Update battery_drop value
                        if battery_drop_idx < len(row):
                            row[battery_drop_idx] = new_drop_value
                        
                        previous_battery_after = battery_after
            except:
                pass
    
    # Re-read original file to preserve comments and formatting
    with open(input_file, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    # Find header line
    header_line_num = None
    for i, line in enumerate(lines):
        if 'battery_drop' in line and 'timestamp' in line:
            header_line_num = i
            break
    
    # Map filtered rows back to lines
    # This is complex due to multi-line rows, so we'll write directly
    with open(output_file, 'w', encoding='utf-8') as f:
        # Write all lines before header
        for i in range(header_line_num):
            f.write(lines[i])
        
        # Write header
        f.write(lines[header_line_num])
        
        # Write filtered data rows with proper CSV formatting
        writer = csv.writer(f, quoting=csv.QUOTE_MINIMAL, lineterminator='\n')
        for i in range(header_idx + 1, len(filtered_rows)):
            writer.writerow(filtered_rows[i])
    
    print(f"✅ Removed {len(rows_to_remove)} rows and recalculated battery_drop")
    return True

def recalculate_battery_drop(input_file, output_file, header_idx, battery_after_idx, battery_drop_idx, header):
    """Recalculate battery_drop values without removing rows."""
    # Read file
    with open(input_file, 'r', encoding='utf-8') as f:
        content = f.read()
    
    reader = csv.reader(io.StringIO(content))
    all_rows = list(reader)
    
    # Recalculate battery_drop
    previous_battery_after = None
    for i in range(header_idx + 1, len(all_rows)):
        row = all_rows[i]
        if len(row) == len(header) and len(row) > battery_after_idx:
            try:
                battery_after_str = str(row[battery_after_idx]).strip()
                if battery_after_str and is_numeric(battery_after_str):
                    battery_after = float(battery_after_str)
                    # Only process valid battery values (0-100)
                    if 0 <= battery_after <= 100:
                        if previous_battery_after is not None:
                            battery_drop = previous_battery_after - battery_after
                            new_drop_value = str(int(battery_drop)) if battery_drop == int(battery_drop) else str(battery_drop)
                        else:
                            new_drop_value = '0'
                        
                        if battery_drop_idx < len(row):
                            row[battery_drop_idx] = new_drop_value
                        
                        previous_battery_after = battery_after
            except:
                pass
    
    # Write back
    with open(input_file, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    header_line_num = None
    for i, line in enumerate(lines):
        if 'battery_drop' in line and 'timestamp' in line:
            header_line_num = i
            break
    
    with open(output_file, 'w', encoding='utf-8') as f:
        for i in range(header_line_num):
            f.write(lines[i])
        f.write(lines[header_line_num])
        
        writer = csv.writer(f, quoting=csv.QUOTE_MINIMAL, lineterminator='\n')
        for i in range(header_idx + 1, len(all_rows)):
            writer.writerow(all_rows[i])
    
    return True

def main():
    """Main function to fix all three CSV files."""
    results_dir = 'results'
    files_to_fix = ['2bit.csv', '3bit.csv', '4bit.csv']
    
    for filename in files_to_fix:
        input_file = os.path.join(results_dir, filename)
        if not os.path.exists(input_file):
            print(f"⚠️  File not found: {input_file}")
            continue
        
        # Fix the file
        fix_early_termination(input_file, input_file)
    
    print("\n✅ All files processed!")

if __name__ == '__main__':
    main()
