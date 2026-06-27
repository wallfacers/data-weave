#!/bin/bash
echo "Orders pipeline completed at $(date)" | curl -X POST -d @- https://hooks.example.com/dingtalk
