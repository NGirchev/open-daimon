# Ubuntu Server Energy Optimization

Documentation for optimizing power consumption of Ubuntu Server on a laptop for the AI Bot project.

## ✅ What Has Been Done

### 1. TLP (Automatic Power Optimization)
```bash
sudo apt install tlp tlp-rdw
sudo systemctl enable tlp
sudo systemctl start tlp
```
**Status**: ✅ Installed and running  
**Effect**: Automatic optimization of CPU, disks, network, USB  
**Rollback**: `sudo systemctl disable tlp && sudo systemctl stop tlp`

### 2. Disabling Bluetooth
```bash
sudo systemctl disable bluetooth
```
**Status**: ✅ Disabled  
**Effect**: Power savings when Bluetooth is not used  
**Rollback**: `sudo systemctl enable bluetooth && sudo systemctl start bluetooth`

### 3. Installing cpufrequtils
```bash
sudo apt install cpufrequtils
```
**Status**: ✅ Installed  
**Effect**: Ability to manage CPU frequency  
**Usage**: 
- `sudo cpufreq-set -g ondemand` - balance performance/power consumption
- `sudo cpufreq-set -g powersave` - maximum power savings
- `sudo cpufreq-set -g performance` - maximum performance

### 4. Disabling Sleep/Hibernation Modes
```bash
sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target
```
**Status**: ✅ Disabled  
**Effect**: Prevents accidental system sleep  
**Rollback**: `sudo systemctl unmask sleep.target suspend.target hibernate.target hybrid-sleep.target`

### 5. Configuring systemd-logind
```bash
sudo nano /etc/systemd/logind.conf
# Configured behavior when laptop lid is closed
```
**Status**: ✅ Configured  
**File**: `/etc/systemd/logind.conf`  
**Rollback**: Restore default settings in `/etc/systemd/logind.conf`

## 🔧 Additional Optimization Options

### 1. CPU Governor (Frequency Management Mode)
**Current state**: cpufrequtils is installed, but the mode is not set explicitly

**Recommendation**: Set the `ondemand` mode (balanced)
```bash
# Set ondemand (recommended for development)
sudo cpufreq-set -g ondemand

# Check current mode
cpufreq-info

# Or via systemd
sudo systemctl enable cpupower
sudo cpupower frequency-set -g ondemand
```

**Alternatives**:
- `powersave` - maximum savings (slower under load)
- `performance` - maximum performance (higher power consumption)
- `conservative` - smooth frequency scaling

### 2. Disabling Unnecessary Services
**Check and disable**:
```bash
# List all running services
sudo systemctl list-units --type=service --state=running

# Example services that can be disabled (if not used):
sudo systemctl disable snapd  # If you don't use snap
sudo systemctl disable avahi-daemon  # If mDNS is not needed
sudo systemctl disable cups  # If no printer
sudo systemctl disable ModemManager  # If no modem
```

### 3. Disk Optimization
```bash
# Add noatime in /etc/fstab to reduce disk writes
sudo nano /etc/fstab
# Edit disk lines, add noatime:
# /dev/sda1 / ext4 defaults,noatime 0 1

# Disable file indexing (if not needed)
sudo systemctl disable tracker-extract tracker-miner-fs tracker-store
```

### 4. Network Optimization
```bash
# Disable Wake-on-LAN (if not needed)
sudo ethtool -s eth0 wol d

# For WiFi (if used)
sudo iw dev wlan0 set power_save on
```

### 5. Docker Container Optimization
```bash
# Stop unused containers
docker ps -a
docker stop <container_id>

# Clean up unused resources
docker system prune -a

# In docker-compose.yml you can add limits:
# deploy:
#   resources:
#     limits:
#       cpus: '1.0'
#       memory: 512M
```

### 6. Kernel Settings for Power Saving
```bash
# Add kernel parameters
sudo nano /etc/default/grub
# In GRUB_CMDLINE_LINUX_DEFAULT add:
# intel_pstate=passive processor.max_cstate=2

sudo update-grub
sudo reboot
```

### 7. Power Consumption Monitoring
```bash
# Install powertop for analysis
sudo apt install powertop

# Calibration (one-time)
sudo powertop --calibrate

# View what consumes power
sudo powertop
```

## ⚠️ What May Need to Be Restored

### 1. Sleep/Hibernation Modes
**If you need to restore sleep capability**:
```bash
sudo systemctl unmask sleep.target suspend.target hibernate.target hybrid-sleep.target
sudo systemctl restart systemd-logind
```

**Verification**:
```bash
sudo systemctl suspend  # Should work
```

### 2. Bluetooth
**If Bluetooth is needed**:
```bash
sudo systemctl enable bluetooth
sudo systemctl start bluetooth
```

### 3. CPU Governor
**If maximum performance is needed**:
```bash
sudo cpufreq-set -g performance
```

**If maximum power savings is needed**:
```bash
sudo cpufreq-set -g powersave
```

### 4. TLP
**If you need to temporarily disable TLP**:
```bash
sudo tlp stop
```

**Re-enable**:
```bash
sudo tlp start
```

**Fully disable**:
```bash
sudo systemctl disable tlp
sudo systemctl stop tlp
```

### 5. Disabled Services
**Restore a service**:
```bash
sudo systemctl enable <service-name>
sudo systemctl start <service-name>
```

## 📊 Power Consumption Monitoring

### 1. PowerTOP (Primary Tool)

**Installation**:
```bash
sudo apt install powertop
```

**Calibration (one-time, takes ~5 minutes)**:
```bash
sudo powertop --calibrate
# System will flash the screen and test various components
```

**Interactive mode**:
```bash
sudo powertop
# Shows:
# - Top processes by power consumption
# - CPU frequencies
# - Device states (USB, PCIe, WiFi)
# - Tunables (settings that can be changed)
```

**Export report to HTML**:
```bash
sudo powertop --html=power_report.html
# Opens browser with detailed report
```

**Automatic mode (for server)**:
```bash
# Generate report every 10 seconds
sudo powertop --time=10 --html=power_report.html

# Or in background
nohup sudo powertop --time=60 --html=power_report.html &
```

### 2. TLP Statistics

**Basic status**:
```bash
sudo tlp-stat -s
# Shows: TLP status, mode (AC/BAT), battery level
```

**Detailed information**:
```bash
# All TLP settings
sudo tlp-stat -c

# Battery information
sudo tlp-stat -b

# CPU information
sudo tlp-stat -p

# Disk information
sudo tlp-stat -d

# Temperature information
sudo tlp-stat -t

# Full statistics
sudo tlp-stat
```

**Export to file**:
```bash
sudo tlp-stat > tlp_report.txt
```

### 3. CPU Frequency and Governor

**Current CPU frequencies**:
```bash
# Via cpufreq-info
cpufreq-info

# Or via sysfs
cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq
cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq
cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_min_freq

# Current governor
cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor
```

**Real-time monitoring**:
```bash
# Watch frequencies every second
watch -n 1 'cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq'

# Or via cpupower
sudo apt install linux-tools-common linux-tools-generic
watch -n 1 'cpupower frequency-info'
```

### 4. Battery Monitoring (if available)

**Via upower**:
```bash
# Install
sudo apt install upower

# Battery information
upower -i /org/freedesktop/UPower/devices/battery_BAT0

# Real-time monitoring
watch -n 1 'upower -i /org/freedesktop/UPower/devices/battery_BAT0 | grep -E "(percentage|energy-rate|time)"'
```

**Via sysfs**:
```bash
# Charge level
cat /sys/class/power_supply/BAT0/capacity

# Status (Charging/Discharging)
cat /sys/class/power_supply/BAT0/status

# Current consumption (in µW)
cat /sys/class/power_supply/BAT0/power_now

# Time remaining
cat /sys/class/power_supply/BAT0/time_to_empty
```

**Battery monitoring script**:
```bash
#!/bin/bash
# Save as monitor_battery.sh
while true; do
    clear
    echo "=== Battery Monitoring ==="
    echo "Level: $(cat /sys/class/power_supply/BAT0/capacity)%"
    echo "Status: $(cat /sys/class/power_supply/BAT0/status)"
    echo "Consumption: $(($(cat /sys/class/power_supply/BAT0/power_now) / 1000000))W"
    echo "Time to empty: $(cat /sys/class/power_supply/BAT0/time_to_empty) minutes"
    echo "Time: $(date '+%H:%M:%S')"
    sleep 5
done
```

### 5. Process and Resource Monitoring

**Top processes by CPU**:
```bash
# Install htop (more convenient than top)
sudo apt install htop

# Run
htop

# Or via top
top

# Top 10 processes by CPU
ps aux --sort=-%cpu | head -11

# Top 10 processes by memory
ps aux --sort=-%mem | head -11
```

**Disk monitoring**:
```bash
# Install iotop
sudo apt install iotop

# Real-time I/O monitoring
sudo iotop

# Disk statistics
iostat -x 1
```

**Network monitoring**:
```bash
# Install iftop
sudo apt install iftop

# Network traffic monitoring
sudo iftop

# Network statistics
iftop -i eth0 -t -s 10
```

### 6. Component Temperature

**CPU temperature**:
```bash
# Install sensors
sudo apt install lm-sensors

# Initialization (one-time)
sudo sensors-detect

# View temperature
sensors

# CPU only
sensors | grep -i cpu

# Real-time monitoring
watch -n 1 sensors
```

**Temperature via sysfs**:
```bash
# CPU temperature (in millidegrees)
cat /sys/class/thermal/thermal_zone*/temp

# In degrees
for i in /sys/class/thermal/thermal_zone*/temp; do
    echo "$i: $(($(cat $i) / 1000))°C"
done
```

### 7. System Metrics

**Via systemd-cgtop**:
```bash
# cgroups monitoring (CPU, memory, I/O)
systemd-cgtop

# Or
systemctl status
```

**Via vmstat**:
```bash
# System statistics every second
vmstat 1

# Shows: CPU, memory, swap, I/O, interrupts
```

**Via iostat**:
```bash
# Install sysstat
sudo apt install sysstat

# Statistics every 2 seconds
iostat -x 2
```

### 8. Creating a Comprehensive Monitoring Script

**Script power_monitor.sh**:
```bash
#!/bin/bash
# Save as power_monitor.sh
# chmod +x power_monitor.sh

while true; do
    clear
    echo "=== Power Consumption Monitoring ==="
    echo ""
    
    # CPU
    echo "=== CPU ==="
    echo "Governor: $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)"
    echo "Frequency: $(($(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq) / 1000)) MHz"
    echo "Load: $(top -bn1 | grep "Cpu(s)" | awk '{print $2}')"
    echo ""
    
    # Battery (if present)
    if [ -f /sys/class/power_supply/BAT0/capacity ]; then
        echo "=== Battery ==="
        echo "Level: $(cat /sys/class/power_supply/BAT0/capacity)%"
        echo "Status: $(cat /sys/class/power_supply/BAT0/status)"
        if [ -f /sys/class/power_supply/BAT0/power_now ]; then
            echo "Consumption: $(($(cat /sys/class/power_supply/BAT0/power_now) / 1000000))W"
        fi
        echo ""
    fi
    
    # Temperature
    if command -v sensors &> /dev/null; then
        echo "=== Temperature ==="
        sensors | grep -E "(CPU|Core|Package)" | head -3
        echo ""
    fi
    
    # TLP status
    if command -v tlp-stat &> /dev/null; then
        echo "=== TLP ==="
        sudo tlp-stat -s | grep -E "(Mode|Battery|AC)" | head -3
        echo ""
    fi
    
    # Top processes by CPU
    echo "=== Top Processes (CPU) ==="
    ps aux --sort=-%cpu | head -6
    echo ""
    
    echo "Time: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "Refreshing every 5 seconds (Ctrl+C to exit)"
    
    sleep 5
done
```

**Usage**:
```bash
chmod +x power_monitor.sh
./power_monitor.sh
```

### 9. Power Consumption Logging

**Create a logging script**:
```bash
#!/bin/bash
# Save as log_power.sh
# chmod +x log_power.sh

LOG_FILE="/var/log/power_monitor.log"

while true; do
    TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
    
    # CPU frequency
    CPU_FREQ=$(($(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq) / 1000))
    CPU_GOV=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)
    
    # Battery
    if [ -f /sys/class/power_supply/BAT0/capacity ]; then
        BAT_CAP=$(cat /sys/class/power_supply/BAT0/capacity)
        BAT_STAT=$(cat /sys/class/power_supply/BAT0/status)
        if [ -f /sys/class/power_supply/BAT0/power_now ]; then
            BAT_PWR=$(($(cat /sys/class/power_supply/BAT0/power_now) / 1000000))
        else
            BAT_PWR="N/A"
        fi
    else
        BAT_CAP="N/A"
        BAT_STAT="N/A"
        BAT_PWR="N/A"
    fi
    
    # Write to log
    echo "$TIMESTAMP | CPU: ${CPU_FREQ}MHz ($CPU_GOV) | Battery: ${BAT_CAP}% ($BAT_STAT) ${BAT_PWR}W" >> $LOG_FILE
    
    sleep 60  # Every minute
done
```

**Run in background**:
```bash
nohup ./log_power.sh &
```

**View logs**:
```bash
tail -f /var/log/power_monitor.log
```

### 10. Automatic Reports

**Daily report via cron**:
```bash
# Add to crontab
sudo crontab -e

# Generate report every day at 23:00
0 23 * * * /usr/bin/sudo powertop --html=/var/log/power_report_$(date +\%Y\%m\%d).html --time=60
```

## 📊 Effectiveness Check

### Quick Check of Current State
```bash
# TLP status
sudo tlp-stat -s

# Current CPU settings
sudo tlp-stat -p
cpufreq-info

# Power consumption (if powertop is installed)
sudo powertop

# Status of all services
sudo systemctl list-units --type=service --state=running
```

### Log Verification
```bash
# Check suspend/sleep logs
journalctl -b | grep -i suspend

# systemd-logind logs
journalctl -u systemd-logind

# TLP logs
journalctl -u tlp
```

## 🔍 Issues and Solutions

### Issue: vbetool dpms off does not work
**Cause**: vbetool may not work on a server without GUI or with certain graphics cards  
**Solution**: Use other optimization methods (TLP, CPU governor)

### Issue: nvidia-smi is not installed
**Cause**: NVIDIA drivers are not installed (normal for Ubuntu Server)  
**Solution**: GPU is not consuming power through drivers anyway, no additional optimization needed

### Issue: System still consumes a lot of power
**Diagnostics**:
```bash
# Check what is using CPU
top
htop

# Check active processes
ps aux --sort=-%cpu | head -20

# Check disk activity
sudo iotop

# Check network activity
sudo iftop
```

## 📝 Notes

- **Last updated**: 2024
- **System**: Ubuntu Server on laptop
- **Project**: AI Bot (Spring Boot + Docker + PostgreSQL)

## 🔗 Useful Links

- [TLP Documentation](https://linrunner.de/tlp/)
- [Ubuntu Power Management](https://help.ubuntu.com/community/PowerManagement)
- [CPU Frequency Scaling](https://wiki.archlinux.org/title/CPU_frequency_scaling)
