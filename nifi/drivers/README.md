# Drivers JDBC para NiFi

Coloca aquí el conector JDBC de MySQL para que el `DBCPConnectionPool` de NiFi
pueda consultar la base de datos `webhardmon`.

1. Descarga **MySQL Connector/J** (Platform Independent, `.jar`):
   https://dev.mysql.com/downloads/connector/j/
   Ejemplo: `mysql-connector-j-8.4.0.jar`

2. Copia el `.jar` en esta carpeta (`nifi/drivers/`).

3. En el `DBCPConnectionPool` de NiFi configura:
   - **Database Driver Location(s)**: `/opt/nifi/drivers/mysql-connector-j-8.4.0.jar`
   - **Database Driver Class Name**: `com.mysql.cj.jdbc.Driver`
   - **Database Connection URL**: `jdbc:mysql://mysql:3306/webhardmon`
   - **Database User**: `root`  ·  **Password**: `root`

> El contenedor monta esta carpeta en `/opt/nifi/drivers` en modo solo lectura.
