#!/bin/bash
# 清理 Hoppscotch 上以 xxxx 开头的集合

TOKEN=$1
SERVER=$2

echo "=== 查询所有根集合 ==="
ROOTS=$(curl -s "${SERVER}graphql" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"query { rootRESTUserCollections(take: 1000) { id title } }"}')

# 提取匹配的集合 ID，每行一个
IDS=$(echo "$ROOTS" | python3 -c "
import json, sys
data = json.load(sys.stdin)
cols = data['data']['rootRESTUserCollections']
to_delete = [c for c in cols if c['title'].startswith('[dlyx-b-data-analysis]')]
print(f'Found {len(to_delete)} collections to delete.', file=sys.stderr)
for c in to_delete:
    print(f'  {c[\"id\"]}: {c[\"title\"]}', file=sys.stderr)
    print(c['id'])
")

echo ""
echo "=== 开始删除 ==="
for id in $IDS; do
    echo "Deleting: $id"
    RESP=$(curl -s "${SERVER}graphql" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer $TOKEN" \
      -d "{\"query\":\"mutation { deleteUserCollection(userCollectionID: \\\"$id\\\") }\"}")
    echo "  Response: $RESP"
done

echo ""
echo "=== 删除完成 ==="
