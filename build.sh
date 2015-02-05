mvn package
cp target/guacamole-auth-hmac-1.0-SNAPSHOT.jar /var/lib/tomcat7/webapps/rdp/WEB-INF/lib/
/etc/init.d/tomcat7 restart
tail -f /var/log/tomcat7/catalina.out
