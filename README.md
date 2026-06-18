# 使用方法
[下载authlib-injector](https://authlib-injector.yushi.moe/)

[下载mcbot](https://github.com/fingtest6/mcbot/actions/runs/27740056332) 第一个mcbot-1

放入同一文件夹

解压mcbot-1.zip

确认文件夹里有mcbot-1.0-SNAPSHOT-all.jar和authlib-injector-1.2.7.jar这两个文件

在当前文件夹下输入命令启动

### cmd
```
java -javaagent:authlib-injector-1.2.7.jar=https://littleskin.cn/api/yggdrasil -jar mcbot-1.0-SNAPSHOT-all.jar
```

按照提示输入相关信息
