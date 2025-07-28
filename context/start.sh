#!/bin/bash


# 定义数据目录路径
DATA_DIR="$HOME/data"

# 设置环境变量
export INSTALL_DATA_DIR=$DATA_DIR
# 执行命令
java -Dlogging.config=$LOGGING_CONFIG -Dloader.main=org.thingsboard.server.ThingsboardServerApplication -jar $LIB_DIR/$APP_NAME --spring.config.location=file:$CONFIG_PATH/$CONFIG_NAME
