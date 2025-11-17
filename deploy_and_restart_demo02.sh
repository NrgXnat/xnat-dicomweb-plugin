#!/bin/bash
# Deploy xnat-dicomweb-proxy to demo02 and restart XNAT

set -e

echo "Deploying to demo02..."
scp build/libs/xnat-dicomweb-proxy-1.1.1.jar demo02:/home/james/xnat-docker-compose/xnat/plugins/

echo "Removing datatype plugin temporarily..."
ssh demo02 "rm -f /home/james/xnat-docker-compose/xnat/plugins/*datatype* || true"

echo "Redeploying XNAT on demo02..."
ssh demo02 "cd xnat-docker-compose && ./redeploy_morpheus.sh"

echo "Waiting for XNAT to start..."
sleep 60

echo "Checking if plugin loaded..."
ssh demo02 "docker logs --tail 100 xnat-docker-compose_xnat-web_1 2>&1 | grep -i 'dicomweb\|error\|exception' | tail -20 || echo 'No specific plugin logs found'"

echo "Deployment complete! Plugin should be available at http://demo02/swagger-ui.html"
