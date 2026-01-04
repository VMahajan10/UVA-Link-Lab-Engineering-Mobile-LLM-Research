#!/usr/bin/env python3
"""
Fix CSV files to:
1. Calculate battery_drop as change between queries (previous query's battery_after - current query's battery_after)
2. Fix any non-numeric values in numeric columns
3. Handle multi-line CSV rows properly
"""

import csv
import sys
import os
import re
import io

def is_numeric(value):
    """Check if a value is numeric."""
    if not value or not value.strip():
        return False
    value = value.strip()
    # Check for valid number (integer or float, can be negative)
    return bool(re.match(r'^-?\d+\.?\d*$', value))

def fix_csv_file(input_file, output_file):
    """Fix battery_drop and non-numeric values in a CSV file."""
    print(f"Processing {input_file}...")
    
    # Read the entire file and handle multi-line CSV rows
    with open(input_file, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Use csv.reader to properly handle multi-line fields
    reader = csv.reader(io.StringIO(content))
    all_rows = list(reader)
    
    # Find the header row
    header_idx = None
    battery_drop_idx = None
    battery_after_idx = None
    header = None
    numeric_cols = {}
    
    for i, row in enumerate(all_rows):
        if len(row) > 0 and 'battery_drop' in str(row) and 'timestamp' in str(row):
            header_idx = i
            header = row
            try:
                battery_drop_idx = header.index('battery_drop')
                battery_after_idx = header.index('battery_after')
                battery_before_idx = header.index('battery_before')
                query_number_idx = header.index('query_number')
                
                # Define numeric columns
                numeric_cols = {
                    'query_number': header.index('query_number'),
                    'prompt_number': header.index('prompt_number'),
                    'response_length_chars': header.index('response_length_chars'),
                    'inference_time_ms': header.index('inference_time_ms'),
                    'battery_before': battery_before_idx,
                    'battery_after': battery_after_idx,
                    'battery_drop': battery_drop_idx,
                    'cpu_usage_before': header.index('cpu_usage_before'),
                    'cpu_usage_after': header.index('cpu_usage_after'),
                    'cpu_usage_change': header.index('cpu_usage_change'),
                    'memory_usage_before': header.index('memory_usage_before'),
                    'memory_usage_after': header.index('memory_usage_after'),
                    'memory_usage_change_mb': header.index('memory_usage_change_mb'),
                    'temperature_before': header.index('temperature_before'),
                    'temperature_after': header.index('temperature_after'),
                    'temperature_change': header.index('temperature_change'),
                }
                break
            except:
                continue
    
    if header_idx is None:
        print(f"ERROR: Could not find header row with 'battery_drop' in {input_file}")
        return False
    
    # Process only valid data rows (those with correct number of columns)
    previous_battery_after = None
    fixed_drop_count = 0
    fixed_numeric_count = 0
    
    # First pass: fix all valid data rows
    for i in range(header_idx + 1, len(all_rows)):
        row = all_rows[i]
        
        # Try to extract battery_after even from invalid rows to maintain tracking
        battery_after_from_invalid = None
        if len(row) != len(header) and len(row) > battery_after_idx:
            # Invalid row, but try to extract battery_after if it's in the expected position
            try:
                battery_after_str = str(row[battery_after_idx]).strip()
                if battery_after_str.replace('.', '').replace('-', '').isdigit():
                    battery_after_val = float(battery_after_str)
                    # Only use if it's a reasonable battery value (0-100)
                    if 0 <= battery_after_val <= 100:
                        battery_after_from_invalid = battery_after_val
            except:
                pass
        
        # Only process rows with correct number of columns (valid data rows)
        if len(row) != len(header):
            # Invalid row (continuation line) - update tracking if we found battery_after
            if battery_after_from_invalid is not None:
                previous_battery_after = battery_after_from_invalid
            continue
        
        # Skip if row doesn't have enough columns (safety check)
        if len(row) <= max(battery_drop_idx, battery_after_idx):
            continue
        
        try:
            # Get battery_after value
            battery_after_str = str(row[battery_after_idx]).strip()
            
            # Fix non-numeric battery_after if needed
            if battery_after_str and not is_numeric(battery_after_str):
                # Try to extract number from the string
                match = re.search(r'\d+', battery_after_str)
                if match:
                    battery_after_str = match.group()
                    row[battery_after_idx] = battery_after_str
                    fixed_numeric_count += 1
            
            if battery_after_str and is_numeric(battery_after_str):
                battery_after = float(battery_after_str)
            elif battery_after_from_row is not None:
                # Use the value we extracted earlier
                battery_after = battery_after_from_row
                row[battery_after_idx] = str(battery_after)
            else:
                # Invalid value, skip this row but don't break tracking
                continue
            
            # Fix other numeric columns
            for col_name, col_idx in numeric_cols.items():
                if col_idx < len(row):
                    value = str(row[col_idx]).strip()
                    if value and not is_numeric(value):
                        # Try to extract number
                        match = re.search(r'-?\d+\.?\d*', value)
                        if match:
                            new_value = match.group()
                            row[col_idx] = new_value
                            fixed_numeric_count += 1
                        else:
                            # If no number found, set to 0
                            row[col_idx] = '0'
                            fixed_numeric_count += 1
            
            # Calculate battery drop (change between queries)
            # battery_drop = previous query's battery_after - current query's battery_after
            if previous_battery_after is not None:
                battery_drop = previous_battery_after - battery_after
                new_drop_value = str(int(battery_drop)) if battery_drop == int(battery_drop) else str(battery_drop)
            else:
                # First query - set to 0
                new_drop_value = '0'
            
            # Update battery_drop value
            row[battery_drop_idx] = new_drop_value
            fixed_drop_count += 1
            
            # Update previous battery_after for next iteration
            previous_battery_after = battery_after
            
        except (ValueError, IndexError, Exception) as e:
            # If anything fails, skip this row
            continue
    
    # Now reconstruct the file preserving original structure
    # Re-read file to preserve comment lines and formatting
    with open(input_file, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    # Find header line number in original file
    header_line_num = None
    for i, line in enumerate(lines):
        if 'battery_drop' in line and 'timestamp' in line:
            header_line_num = i
            break
    
    # Second pass: write the fixed rows back to file
    modified_lines = []
    data_row_idx = header_idx + 1  # Start after header in all_rows
    
    for i, line in enumerate(lines):
        if i < header_line_num:
            # Keep header and comment lines as-is
            modified_lines.append(line)
            continue
        
        if i == header_line_num:
            # Keep header line
            modified_lines.append(line)
            continue
        
        # Skip empty lines
        if not line.strip():
            modified_lines.append(line)
            continue
        
        # Get the corresponding row from parsed CSV
        if data_row_idx >= len(all_rows):
            # End of data, keep remaining lines as-is
            modified_lines.append(line)
            continue
        
        row = all_rows[data_row_idx]
        
        # Check if this is a valid data row (has correct number of columns)
        if len(row) != len(header):
            # This might be a continuation line from a multi-line field
            # Keep the original line as-is
            modified_lines.append(line)
            # Don't advance data_row_idx - this line is part of previous row
            continue
        
        # Valid row - advance index
        data_row_idx += 1
        
        # Skip if row doesn't have enough columns (safety check)
        if len(row) <= max(battery_drop_idx, battery_after_idx):
            modified_lines.append(line)
            continue
        
        # Reconstruct line with proper CSV formatting
        try:
            output = io.StringIO()
            writer = csv.writer(output, quoting=csv.QUOTE_MINIMAL, lineterminator='')
            writer.writerow(row)
            new_line = output.getvalue()
            
            # Preserve newline
            if line.endswith('\n'):
                new_line += '\n'
            elif line.endswith('\r\n'):
                new_line += '\r\n'
            
            modified_lines.append(new_line)
        except:
            # If reconstruction fails, keep original line
            modified_lines.append(line)
    
    # Write modified content
    with open(output_file, 'w', encoding='utf-8') as f:
        f.writelines(modified_lines)
    
    print(f"✅ Fixed {fixed_drop_count} battery_drop values")
    print(f"✅ Fixed {fixed_numeric_count} non-numeric values")
    return True

def main():
    """Main function to fix all three CSV files."""
    results_dir = 'results'
    files_to_fix = ['2bit.csv', '3bit.csv', '4bit.csv']
    
    # Restore from backup first
    for filename in files_to_fix:
        input_file = os.path.join(results_dir, filename)
        backup_file = input_file + '.backup'
        
        if os.path.exists(backup_file):
            import shutil
            shutil.copy2(backup_file, input_file)
            print(f"📋 Restored {input_file} from backup")
    
    # Now fix the files
    for filename in files_to_fix:
        input_file = os.path.join(results_dir, filename)
        if not os.path.exists(input_file):
            print(f"⚠️  File not found: {input_file}")
            continue
        
        # Fix the file
        fix_csv_file(input_file, input_file)
    
    print("\n✅ All files processed!")

if __name__ == '__main__':
    main()
