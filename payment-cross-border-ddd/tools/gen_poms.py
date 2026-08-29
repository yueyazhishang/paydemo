#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成所有 Maven pom.xml"""
import os

PARENT = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.demo</groupId>
    <artifactId>payment-ddd-demo</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <name>payment-ddd-demo</name>
    <description>DDD 支付系统演示工程：9 通道归一化 + 幂等与一致性</description>

    <modules>
        <module>payment-shared-kernel</module>
        <module>payment-domain</module>
        <module>payment-application</module>
        <module>payment-channel-adapter</module>
        <module>payment-infrastructure</module>
        <module>payment-interfaces</module>
        <module>payment-bootstrap</module>
    </modules>

    <properties>
        <java.version>17</java.version>
        <maven.compiler.release>17</maven.compiler.release>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
        <spring-boot.version>3.3.4</spring-boot.version>
        <junit.version>5.10.3</junit.version>
        <maven-compiler-plugin.version>3.13.0</maven-compiler-plugin.version>
        <maven-surefire-plugin.version>3.5.0</maven-surefire-plugin.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.junit</groupId>
                <artifactId>junit-bom</artifactId>
                <version>${junit.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <version>${spring-boot.version}</version>
                </plugin>
            </plugins>
        </pluginManagement>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>${maven-compiler-plugin.version}</version>
                <configuration>
                    <release>${maven.compiler.release}</release>
                    <encoding>UTF-8</encoding>
                    <compilerArgs>
                        <arg>-parameters</arg>
                    </compilerArgs>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>${maven-surefire-plugin.version}</version>
            </plugin>
        </plugins>
    </build>
</project>
"""

def mod(artifact, name, deps, extra=""):
    dep_xml = ""
    for d in deps:
        # 内部模块依赖需要显式版本号（dependencyManagement 里用的是 spring-boot BOM，管不到内部模块）
        version_tag = "\n            <version>${project.version}</version>" if d[0] == "com.demo" else ""
        dep_xml += """
        <dependency>
            <groupId>%s</groupId>
            <artifactId>%s</artifactId>%s%s
        </dependency>
""" % (d[0], d[1], version_tag,
       ("\n            <scope>%s</scope>" % d[2]) if len(d) > 2 and d[2] else "")
    return """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.demo</groupId>
        <artifactId>payment-ddd-demo</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>%s</artifactId>
    <name>%s</name>

    <dependencies>%s    </dependencies>
%s</project>
""" % (artifact, name, dep_xml, extra)

LOCAL = ("com.demo", None, None)

POMS = {}

POMS["payment-shared-kernel"] = mod("payment-shared-kernel", "共享内核：Money / 事件 / 异常", [])

POMS["payment-domain"] = mod("payment-domain", "领域层：收单聚合 / 状态机 / 通道能力模型", [
    ("com.demo", "payment-shared-kernel", None),
])

POMS["payment-application"] = mod("payment-application", "应用层：用例编排 / 幂等 / Outbox", [
    ("com.demo", "payment-shared-kernel", None),
    ("com.demo", "payment-domain", None),
])

POMS["payment-channel-adapter"] = mod("payment-channel-adapter", "通道适配层：9 通道归一化实现", [
    ("com.demo", "payment-shared-kernel", None),
    ("com.demo", "payment-domain", None),
])

POMS["payment-infrastructure"] = mod("payment-infrastructure", "基础设施层：仓储 / 幂等存储 / 定时任务", [
    ("com.demo", "payment-shared-kernel", None),
    ("com.demo", "payment-domain", None),
    ("com.demo", "payment-application", None),
    ("com.demo", "payment-channel-adapter", None),
    ("org.springframework.boot", "spring-boot-starter", None),
    ("org.springframework.boot", "spring-boot-starter-data-jpa", None),
    ("org.springframework", "spring-tx", None),
])

POMS["payment-interfaces"] = mod("payment-interfaces", "接入层：REST API / 回调入口", [
    ("com.demo", "payment-shared-kernel", None),
    ("com.demo", "payment-domain", None),
    ("com.demo", "payment-application", None),
    ("org.springframework.boot", "spring-boot-starter-web", None),
])

POMS["payment-bootstrap"] = mod("payment-bootstrap", "启动器", [
    ("com.demo", "payment-shared-kernel", None),
    ("com.demo", "payment-domain", None),
    ("com.demo", "payment-application", None),
    ("com.demo", "payment-channel-adapter", None),
    ("com.demo", "payment-infrastructure", None),
    ("com.demo", "payment-interfaces", None),
    ("org.springframework.boot", "spring-boot-starter-web", None),
    ("org.springframework.boot", "spring-boot-starter-test", "test"),
], extra="""    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
""")


def write_poms(base):
    full = os.path.join(base, "pom.xml")
    with open(full, "w", encoding="utf-8") as f:
        f.write(PARENT)
    print("WROTE pom.xml (parent)")

    for module, content in POMS.items():
        full = os.path.join(base, module, "pom.xml")
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as f:
            f.write(content)
        print("WROTE", module + "/pom.xml")


if __name__ == "__main__":
    write_poms(os.path.dirname(os.path.abspath(__file__)))
