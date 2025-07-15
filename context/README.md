
# 构建 All In One ThingsBoard 的 Docker 镜像

## 使用方式
### 1.构建镜像
- 将 application 中 src/main/data 目录复制到 context 根目录中
- 将 application 中 target 中打包之后的 thingsboard-4.1.0-boot.jar 放入到 context 根目录中
- 进入 context 目录执行 `docker build -t 镜像名称:版本号 .` 构建镜像即可


### 2.docker-compose 方式初始化
- 进入 context 目录中 services 目录中的 `iotcloud-版本号` 目录
- 修改 iotcloud-版本号 目录中 `conf` 中 `thingsboard.yml` 配置信息
- 执行 `docker-compose -f docker-compose-install.yaml up` 进行初始化配置

> 注意: 使用时请先调整conf thingsboard.yaml 信息以及 docker-compose-install.yaml 的信息。

### 3.docker-compose 方式启动
- 进入 context 目录中 services 目录中的 `iotcloud-版本号` 目录
- 修改 iotcloud-版本号 目录中 `conf` 中 `thingsboard.yml` 配置信息
- 执行 `docker-compose up -d` 进行启动

> 注意: 使用时请先调整conf thingsboard.yaml 信息以及 docker-compose.yaml 的信息。
