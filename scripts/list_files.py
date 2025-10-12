#!/usr/bin/env python3
"""
Script to list files in Hugging Face repositories
"""

from huggingface_hub import list_repo_files

repos = [
    "irish-quant/Qwen-Qwen2.5-Coder-3B-Instruct-2bit",
    "irish-quant/Qwen-Qwen2.5-Coder-3B-Instruct-3bit", 
    "irish-quant/Qwen-Qwen2.5-Coder-3B-Instruct-4bit"
]

for repo in repos:
    print(f"\n📁 Files in {repo}:")
    try:
        files = list_repo_files(repo)
        for file in files:
            if file.endswith('.gguf'):
                print(f"  ✓ {file}")
    except Exception as e:
        print(f"  ❌ Error: {e}")
