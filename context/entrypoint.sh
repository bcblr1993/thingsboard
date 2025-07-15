#!/bin/bash

# 执行安装脚本
./install.sh

# 检查install.sh脚本执行是否成功
if [ $? -eq 0 ]; then
    # 如果install.sh成功执行，则执行start.sh
    ./start.sh
else
    echo "install.sh failed, exiting..."
    exit 1
fi
