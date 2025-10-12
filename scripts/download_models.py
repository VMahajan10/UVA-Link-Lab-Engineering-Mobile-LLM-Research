#!/usr/bin/env python3
"""
Mobile LLM Battery Benchmark - Model Download Script

Downloads quantized GGUF models from Hugging Face for battery benchmarking.
Downloads 2-bit, 3-bit, and 4-bit quantized Qwen2.5-0.5B-Instruct models.

Requirements:
    pip install huggingface_hub

Usage:
    python scripts/download_models.py
"""

from huggingface_hub import hf_hub_download
import os
from pathlib import Path

# Model configuration
MODELS = [
    {
        "repo_id": "irish-quant/Qwen-Qwen2.5-Coder-3B-Instruct-2bit",
        "filename": "Qwen2.5-Coder-3B-Instruct-Q2_K.gguf",
        "local_dir": "models/2bit",
        "description": "2-bit quantized (0.5B, smallest, fastest)"
    },
    {
        "repo_id": "irish-quant/Qwen-Qwen2.5-Coder-3B-Instruct-3bit",
        "filename": "Qwen2.5-Coder-3B-Instruct-Q3_K_M.gguf", 
        "local_dir": "models/3bit",
        "description": "3-bit quantized (0.6B, balanced)"
    },
    {
        "repo_id": "irish-quant/Qwen-Qwen2.5-Coder-3B-Instruct-4bit",
        "filename": "Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf",
        "local_dir": "models/4bit", 
        "description": "4-bit quantized (highest quality)"
    }
]

def format_file_size(size_bytes):
    """Convert bytes to human readable format."""
    if size_bytes == 0:
        return "0 B"
    
    for unit in ['B', 'KB', 'MB', 'GB']:
        if size_bytes < 1024.0:
            return f"{size_bytes:.1f} {unit}"
        size_bytes /= 1024.0
    return f"{size_bytes:.1f} TB"

def create_directory(path):
    """Create directory if it doesn't exist."""
    Path(path).mkdir(parents=True, exist_ok=True)
    print(f"✓ Created directory: {path}")

def file_exists(file_path):
    """Check if file exists and return its size."""
    if os.path.exists(file_path):
        size = os.path.getsize(file_path)
        return True, size
    return False, 0

def download_model(model_info):
    """
    Download a single model file.
    
    Args:
        model_info: Dictionary containing repo_id, filename, local_dir, description
    
    Returns:
        bool: True if successful, False otherwise
    """
    repo_id = model_info["repo_id"]
    filename = model_info["filename"]
    local_dir = model_info["local_dir"]
    description = model_info["description"]
    
    print(f"\n📥 Downloading {description}")
    print(f"   Repository: {repo_id}")
    print(f"   File: {filename}")
    print(f"   Directory: {local_dir}")
    
    # Create directory if it doesn't exist
    create_directory(local_dir)
    
    # Check if file already exists
    file_path = os.path.join(local_dir, filename)
    exists, existing_size = file_exists(file_path)
    
    if exists:
        print(f"✓ File already exists: {file_path}")
        print(f"  Size: {format_file_size(existing_size)}")
        return True
    
    try:
        print("   Downloading... (this may take a while)")
        
        # Download the model using huggingface_hub
        downloaded_path = hf_hub_download(
            repo_id=repo_id,
            filename=filename,
            local_dir=local_dir,
            local_dir_use_symlinks=False  # Copy actual file, not symlink
        )
        
        # Verify download and get file size
        if os.path.exists(downloaded_path):
            file_size = os.path.getsize(downloaded_path)
            print(f"✓ Successfully downloaded: {downloaded_path}")
            print(f"  Size: {format_file_size(file_size)}")
            return True
        else:
            print(f"❌ Download failed: File not found at {downloaded_path}")
            return False
            
    except Exception as e:
        print(f"❌ Error downloading {filename}: {str(e)}")
        return False

def main():
    """Main function to download all models."""
    print("Mobile LLM Battery Benchmark - Model Downloader")
    print("=" * 50)
    print(f"Total models: {len(MODELS)}")
    print("Models: Qwen2.5-Coder-3B-Instruct (2bit, 3bit, 4bit)")
    
    # Download each model
    success_count = 0
    total_size = 0
    
    for i, model_info in enumerate(MODELS, 1):
        print(f"\n[{i}/{len(MODELS)}] Processing {model_info['description']}")
        
        if download_model(model_info):
            success_count += 1
            # Add to total size
            file_path = os.path.join(model_info["local_dir"], model_info["filename"])
            if os.path.exists(file_path):
                total_size += os.path.getsize(file_path)
        else:
            print(f"❌ Failed to download {model_info['filename']}")
    
    # Print summary
    print("\n" + "=" * 50)
    print("📊 Download Summary")
    print("=" * 50)
    print(f"✓ Successfully downloaded: {success_count}/{len(MODELS)} models")
    print(f"📦 Total size: {format_file_size(total_size)}")
    
    if success_count == len(MODELS):
        print("\n🎉 All models downloaded successfully!")
        print("\nNext steps:")
        print("1. Copy models to app/src/main/assets/models/")
        print("2. Build the project: ./gradlew assembleDebug")
        print("3. Install on device: adb install app/build/outputs/apk/debug/app-debug.apk")
    else:
        print(f"\n⚠️  {len(MODELS) - success_count} model(s) failed to download")
        print("Please check your internet connection and try again")

if __name__ == "__main__":
    main()