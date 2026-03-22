#!/bin/bash
mkdir -p /etc/systemd/system/ollama.service.d
cat > /tmp/override.conf << 'EOF'
[Service]
Environment="OLLAMA_HOST=0.0.0.0"
EOF
sudo cp /tmp/override.conf /etc/systemd/system/ollama.service.d/override.conf
sudo systemctl daemon-reload
sudo systemctl restart ollama
echo "Done. Ollama now listens on 0.0.0.0"
