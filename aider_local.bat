@echo off
rem Run this inside your project folder (where .git is). Edits are NOT auto-committed.
set "OLLAMA_API_BASE=http://127.0.0.1:11434"
aider --model ollama_chat/qwen2.5-coder-14b-8k --no-auto-commits --no-show-model-warnings %*
