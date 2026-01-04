#!/usr/bin/env python3
"""
Fix battery_drop values in CSV files by calculating the actual battery drop
from the previous row's battery level. Preserves original CSV formatting exactly.
"""

import csv
import sys
import os
import re

def find_field_positions(line, header):
    """Find the start and end positions of each field in the original line."""
    positions = []
    reader = csv.reader([line.rstrip()])
    try:
        row = next(reader)
    except:
        return None
    
    # Use a state machine to find field boundaries
    in_quotes = False
    field_start = 0
    field_idx = 0
    
    i = 0
    while i < len(line):
        char = line[i]
        
        if char == '"':
            in_quotes = not in_quotes
        elif char == ',' and not in_quotes:
            # End of field
            if field_idx < len(row):
                positions.append((field_start, i))
            field_start = i + 1
            field_idx += 1
        elif char == '\n' or char == '\r':
            # End of line
            if field_idx < len(row):
                positions.append((field_start, i))
            break
        
        i += 1
    
    return positions

def fix_battery_drop(input_file, output_file):
    """Fix battery_drop values in a CSV file while preserving exact formatting."""
    print(f"Processing {input_file}...")
    
    # Read the entire file
    with open(input_file, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    # Find the header row
    header_idx = None
    battery_drop_idx = None
    battery_after_idx = None
    header_line = None
    
    for i, line in enumerate(lines):
        if 'battery_drop' in line and 'timestamp' in line:
            header_idx = i
            header_line = line
            # Parse header to find column indices
            reader = csv.reader([line.rstrip()])
            try:
                header = next(reader)
                battery_drop_idx = header.index('battery_drop')
                battery_after_idx = header.index('battery_after')
                break
            except:
                continue
    
    if header_idx is None:
        print(f"ERROR: Could not find header row with 'battery_drop' in {input_file}")
        return False
    
    # Process data rows
    previous_battery_after = None
    modified_lines = []
    fixed_count = 0
    
    for i, line in enumerate(lines):
        if i < header_idx:
            # Keep header and comment lines as-is
            modified_lines.append(line)
            continue
        
        if i == header_idx:
            # Keep header line
            modified_lines.append(line)
            continue
        
        # Skip empty lines
        if not line.strip():
            modified_lines.append(line)
            continue
        
        # Parse CSV row to get values
        try:
            reader = csv.reader([line.rstrip()])
            row = next(reader)
        except:
            # If parsing fails, keep original line
            modified_lines.append(line)
            continue
        
        if len(row) <= max(battery_drop_idx, battery_after_idx):
            # Invalid row, keep as-is
            modified_lines.append(line)
            continue
        
        try:
            # Get battery_after value
            battery_after_str = row[battery_after_idx].strip()
            if battery_after_str and (battery_after_str.replace('.', '').replace('-', '').isdigit() or battery_after_str.isdigit()):
                battery_after = float(battery_after_str)
            else:
                # Invalid value, keep original
                modified_lines.append(line)
                continue
            
            # Calculate battery drop
            if previous_battery_after is not None:
                # Drop is the difference from previous row's battery_after to current row's battery_after
                battery_drop = previous_battery_after - battery_after
                new_drop_value = str(int(battery_drop)) if battery_drop == int(battery_drop) else str(battery_drop)
            else:
                # First data row - set to 0 (no previous value)
                new_drop_value = '0'
            
            # Get old drop value
            old_drop_value = row[battery_drop_idx]
            
            # Only replace if value actually changed
            if old_drop_value != new_drop_value:
                # Find field positions in the original line
                positions = find_field_positions(line, row)
                
                if positions and battery_drop_idx < len(positions):
                    # Get the start and end position of battery_drop field
                    start_pos, end_pos = positions[battery_drop_idx]
                    
                    # Replace just that field in the original line
                    new_line = line[:start_pos] + new_drop_value + line[end_pos:]
                    modified_lines.append(new_line)
                    fixed_count += 1
                else:
                    # Fallback: use regex to find and replace
                    # Pattern: find battery_after, then the next field (battery_drop)
                    # We need to match: battery_after_value,battery_drop_value,
                    pattern = r'(' + re.escape(str(int(battery_after)) if battery_after == int(battery_after) else str(battery_after)) + r',)' + re.escape(old_drop_value) + r'(,|$)'
                    replacement = r'\1' + new_drop_value + r'\2'
                    new_line = re.sub(pattern, replacement, line, count=1)
                    
                    if new_line != line:
                        modified_lines.append(new_line)
                        fixed_count += 1
                    else:
                        # If regex didn't work, keep original
                        modified_lines.append(line)
            else:
                # Value already correct, keep original line
                modified_lines.append(line)
            
            # Update previous battery_after for next iteration
            previous_battery_after = battery_after
            
        except (ValueError, IndexError, Exception) as e:
            # If anything fails, keep original line
            modified_lines.append(line)
            continue
    
    # Write modified content
    with open(output_file, 'w', encoding='utf-8') as f:
        f.writelines(modified_lines)
    
    print(f"✅ Fixed {fixed_count} battery_drop values in {input_file}")
    return True

def main():
    """Main function to fix all three CSV files."""
    results_dir = 'results'
    files_to_fix = ['2bit.csv', '3bit.csv', '4bit.csv']
    
    # Fix the files (they should already be restored from backup)
    for filename in files_to_fix:
        input_file = os.path.join(results_dir, filename)
        if not os.path.exists(input_file):
            print(f"⚠️  File not found: {input_file}")
            continue
        
        # Fix the file
        fix_battery_drop(input_file, input_file)
    
    print("\n✅ All files processed!")

if __name__ == '__main__':
    main()
