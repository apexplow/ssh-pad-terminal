#!/usr/bin/env bash
#
# Interactive release keystore generator for HanTerm GitHub Actions.
# Prompts for all parameters and outputs the 4 GitHub Secrets values.
#
# Usage:
#   chmod +x scripts/gen-release-keystore.sh
#   ./scripts/gen-release-keystore.sh

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${BOLD}=== HanTerm Release Keystore Generator ===${NC}"
echo ""

# --- Alias ---
read -r -p "密钥别名 (alias): " ALIAS
if [[ -z "$ALIAS" ]]; then
  echo -e "${RED}别名不能为空${NC}"
  exit 1
fi

# --- Password ---
read -r -s -p "密钥密码 (storepass/keypass，输入不显示): " PASSWORD
echo ""
if [[ -z "$PASSWORD" ]]; then
  echo -e "${RED}密码不能为空${NC}"
  exit 1
fi

# --- CN ---
read -r -p "CN (姓名/组织名，默认 HanTerm): " CN
CN="${CN:-HanTerm}"

# --- Org ---
read -r -p "O (组织，默认 apexplow): " ORG
ORG="${ORG:-apexplow}"

# --- Country ---
read -r -p "C (国家代码，默认 CN): " COUNTRY
COUNTRY="${COUNTRY:-CN}"

# --- Validity ---
read -r -p "有效期天数 (默认 10000): " VALIDITY
VALIDITY="${VALIDITY:-10000}"

# --- Output filename ---
read -r -p "输出文件名 (默认 release.jks): " OUTFILE
OUTFILE="${OUTFILE:-release.jks}"

echo ""
echo -e "${YELLOW}--- 摘要 ---${NC}"
echo "别名:       $ALIAS"
echo "CN:         $CN"
echo "O:          $ORG"
echo "C:          $COUNTRY"
echo "有效期:     $VALIDITY 天"
echo "输出文件:   $OUTFILE"
echo ""

# --- Confirm ---
read -r -p "确认生成? (y/N): " CONFIRM
if [[ ! "$CONFIRM" =~ ^[Yy]$ ]]; then
  echo "已取消"
  exit 0
fi

echo ""

# --- Generate ---
echo -e "${BOLD}>>> 生成 keystore...${NC}"
keytool -genkeypair \
  -keystore "$OUTFILE" \
  -storetype PKCS12 \
  -keyalg RSA -keysize 2048 -validity "$VALIDITY" \
  -alias "$ALIAS" \
  -storepass "$PASSWORD" -keypass "$PASSWORD" \
  -dname "CN=$CN,O=$ORG,C=$COUNTRY"

echo -e "${GREEN}✓ $OUTFILE 已生成${NC}"

# --- Base64 ---
B64FILE="${OUTFILE}.b64"
base64 -w0 "$OUTFILE" > "$B64FILE"
echo -e "${GREEN}✓ $B64FILE 已生成${NC}"

# --- Output secrets ---
echo ""
echo -e "${BOLD}=== GitHub Actions Secrets ===${NC}"
echo ""
echo -e "请将以下 4 个 Secrets 添加到仓库的 Settings → Secrets and variables → Actions 中："
echo ""

echo -e "${YELLOW}KEYSTORE_BASE64${NC}"
echo "──────────────────────────────────────────"
cat "$B64FILE"
echo ""
echo "──────────────────────────────────────────"
echo ""

echo -e "${YELLOW}KEYSTORE_PASSWORD${NC}"
echo "  $PASSWORD"
echo ""

echo -e "${YELLOW}KEY_ALIAS${NC}"
echo "  $ALIAS"
echo ""

echo -e "${YELLOW}KEY_PASSWORD${NC}"
echo "  $PASSWORD"
echo ""

echo -e "${BOLD}=== 完成 ===${NC}"
echo ""
echo -e "${RED}⚠  请妥善备份 $OUTFILE，丢失后无法发布更新${NC}"
echo -e "${RED}⚠  $OUTFILE 和 $B64FILE 不要提交到 Git${NC}"
