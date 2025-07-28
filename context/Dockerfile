FROM eclipse-temurin:17-jre

# 设置系统环境变量
ENV LANG=C.UTF-8
ENV HOME=/home/sprixin
ENV LOG_DIR=$HOME/logs
ENV LIB_DIR=$HOME/lib
ENV CONFIG_PATH=$HOME/conf
ENV APP_NAME=thingsboard-4.1.0-boot.jar
ENV CONFIG_NAME=thingsboard.yml
ENV LOGGING_CONFIG=$HOME/logback.xml

#设置配置文件环境变量
#ENV SPRING_DRIVER_CLASS_NAME=org.postgresql.Driver
#ENV SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/thingsboard
#ENV SPRING_DATASOURCE_USERNAME=postgres
#ENV SPRING_DATASOURCE_PASSWORD=postgres

# 设置工作目录
WORKDIR $HOME

# 创建日志、配置目录
RUN mkdir -p $LOGS $CONFIG_PATH $LIB_DIR

# 复制外部文件
COPY install.sh install.sh
COPY start.sh start.sh
COPY entrypoint.sh entrypoint.sh
COPY logback.xml $LOGGING_CONFIG
COPY data data
COPY $APP_NAME $LIB_DIR/$APP_NAME
COPY thingsboard.yml $CONFIG_PATH/$CONFIG_NAME

#添加权限
RUN chmod +x *.sh

# 声明暴露端口
EXPOSE 1883
EXPOSE 7070
EXPOSE 8080
EXPOSE 5683-5688/udp

# 声明日志、配置目录
VOLUME $LOG_DIR
VOLUME $CONFIG_PATH

# 启动软件服务
CMD ["/bin/bash","start.sh"]
