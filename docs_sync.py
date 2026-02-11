import os, re, sys, argparse
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from google.auth.transport.requests import Request
from googleapiclient.discovery import build

SCOPES = ['https://www.googleapis.com/auth/documents']

def extract_doc_id(url):
    match = re.search(r"/d/([a-zA-Z0-9-_]+)", url)
    return match.group(1) if match else None

def get_service():
    creds = None
    if os.path.exists('token.json'):
        creds = Credentials.from_authorized_user_file('token.json', SCOPES)
    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
        else:
            flow = InstalledAppFlow.from_client_secrets_file('credentials.json', SCOPES)
            creds = flow.run_local_server(port=0)
        with open('token.json', 'w') as token:
            token.write(creds.to_json())
    return build('docs', 'v1', credentials=creds)

def read_structural_elements(elements):
    """Docs -> Markdown (Pull): 標準機能のインデントを解析してMarkdown化"""
    text = ""
    for value in elements:
        if 'paragraph' in value:
            para = value.get('paragraph')
            para_text = ""
            for el in para.get('elements'):
                content = el.get('textRun', {}).get('content', '')
                if el.get('textRun', {}).get('textStyle', {}).get('bold'):
                    if content.endswith('\n'): para_text += f"**{content[:-1]}**\n"
                    else: para_text += f"**{content}**"
                else: para_text += content
            
            if para_text == "\n": text += "\n"; continue
            
            style = para.get('paragraphStyle', {})
            named_style = style.get('namedStyleType', 'NORMAL_TEXT')
            
            if named_style.startswith('HEADING_'):
                level = named_style.split('_')[1]
                text += "#" * int(level) + " " + para_text.lstrip()
            
            elif 'bullet' in para:
                # 1. NestingLevelを確認
                nesting = para.get('bullet', {}).get('nestingLevel', 0)
                # 2. IndentStartから階層を逆算 (36pt = 1レベル)
                indent_start = style.get('indentStart', {}).get('magnitude', 0)
                if nesting == 0 and indent_start > 36:
                     # 18ptのFirstLine補正を考慮して計算
                     calculated = int((indent_start - 36 + 9) / 18)
                     nesting = max(nesting, calculated)
                
                text += ("  " * nesting) + "* " + para_text.lstrip()
            
            elif style.get('indentStart', {}).get('magnitude', 0) > 0:
                text += "> " + para_text
            else:
                text += para_text
    return text

def push(doc_id, filename):
    """Markdown -> Docs (Push): AIとの互換性を最優先したネイティブ同期"""
    service = get_service()
    if not os.path.exists(filename): return
    
    with open(filename, 'r', encoding='utf-8') as f:
        raw_content = f.read().replace('\r\n', '\n')
    
    lines = raw_content.split('\n')
    if raw_content.endswith('\n'): lines = lines[:-1]

    # --- STEP 0: 既存クリア ---
    doc = service.documents().get(documentId=doc_id).execute()
    end_index = doc.get('body').get('content')[-1].get('endIndex')
    if end_index > 2:
        service.documents().batchUpdate(documentId=doc_id, body={'requests': [{'deleteContentRange': {'range': {'startIndex': 1, 'endIndex': end_index - 1}}}]}).execute()
    
    full_text = ""
    tasks = []
    current_pos = 1

    for line in lines:
        temp_line = line
        style = 'NORMAL_TEXT'
        is_bullet = False
        bullet_level = 0
        is_quote = False
        bold_ranges = []

        if temp_line.startswith('> '):
            is_quote = True; temp_line = temp_line[2:]
        
        match_h = re.match(r'^(#+)\s', temp_line)
        if match_h:
            h_level = len(match_h.group(1))
            if h_level <= 6: style = f'HEADING_{h_level}'; temp_line = temp_line[h_level+1:]
        
        elif not is_quote:
            stripped = temp_line.lstrip()
            if stripped.startswith(('* ', '- ')):
                is_bullet = True
                indent_spaces = len(temp_line) - len(stripped)
                bullet_level = indent_spaces // 2
                temp_line = stripped[2:]

        clean_line = ""
        last_idx = 0
        for m in re.finditer(r'\*\*(.*?)\*\*', temp_line):
            pre_text = temp_line[last_idx:m.start()]
            clean_line += pre_text
            bold_ranges.append((len(clean_line), len(clean_line) + len(m.group(1))))
            clean_line += m.group(1)
            last_idx = m.end()
        clean_line += temp_line[last_idx:]
        
        final_line_text = clean_line + "\n"
        line_len = len(final_line_text)
        
        tasks.append({
            'start': current_pos, 'end': current_pos + line_len,
            'style': style, 'bullet': is_bullet, 'level': bullet_level,
            'quote': is_quote, 'bolds': bold_ranges
        })
        full_text += final_line_text
        current_pos += line_len

    # --- STEP 1: テキスト挿入 ---
    service.documents().batchUpdate(documentId=doc_id, body={'requests': [{'insertText': {'location': {'index': 1}, 'text': full_text}}]}).execute()

    # --- STEP 2: スタイル適用 ---
    requests = []
    for t in tasks:
        s, e = t['start'], t['end']
        
        if t['style'] != 'NORMAL_TEXT':
            requests.append({'updateParagraphStyle': {'range': {'startIndex': s, 'endIndex': e}, 'paragraphStyle': {'namedStyleType': t['style']}, 'fields': 'namedStyleType'}})
        
        if t['quote']:
            requests.append({'updateParagraphStyle': {'range': {'startIndex': s, 'endIndex': e}, 'paragraphStyle': {'indentStart': {'magnitude': 36, 'unit': 'PT'}, 'indentFirstLine': {'magnitude': 36, 'unit': 'PT'}}, 'fields': 'indentStart,indentFirstLine'}})
        
        if t['bullet']:
            # ネイティブ箇条書きを作成
            requests.append({'createParagraphBullets': {'range': {'startIndex': s, 'endIndex': e}, 'bulletPreset': 'BULLET_DISC_CIRCLE_SQUARE'}})
            
            # インデントの計算と適用 (マークは全部●になるが、構造は正しい)
            # Level 0: Text=36, First=18
            # Level 1: Text=54, First=36 (+18ptずつ)
            text_pos = 36 + (18 * t['level'])
            bullet_pos = text_pos - 18
            
            requests.append({
                'updateParagraphStyle': {
                    'range': {'startIndex': s, 'endIndex': e},
                    'paragraphStyle': {
                        'indentStart': {'magnitude': text_pos, 'unit': 'PT'},
                        'indentFirstLine': {'magnitude': bullet_pos, 'unit': 'PT'} 
                    },
                    'fields': 'indentStart,indentFirstLine'
                }
            })
        
        for b_s, b_e in t['bolds']:
            if s + b_e > s + b_s:
                requests.append({'updateTextStyle': {'range': {'startIndex': s + b_s, 'endIndex': s + b_e}, 'textStyle': {'bold': True}, 'fields': 'bold'}})

    # バッチ実行
    batch_size = 50
    for i in range(0, len(requests), batch_size):
        try:
            service.documents().batchUpdate(documentId=doc_id, body={'requests': requests[i:i+batch_size]}).execute()
        except Exception as e:
            print(f"警告: バッチ適用エラー (chunk {i//batch_size}): {e}")

    print(f"成功: {filename} を同期しました (AI Native)。")

def pull(doc_id, filename):
    service = get_service()
    doc = service.documents().get(documentId=doc_id).execute()
    markdown_text = read_structural_elements(doc.get('body').get('content'))
    with open(filename, 'w', encoding='utf-8', newline='\n') as f:
        f.write(markdown_text)
    print(f"成功: {filename} をLF改行で保存しました。")

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['pull', 'push']); parser.add_argument('url')
    args = parser.parse_args()
    doc_id = extract_doc_id(args.url)
    if doc_id:
        if args.mode == 'pull': pull(doc_id, f"{doc_id}.md")
        elif args.mode == 'push': push(doc_id, f"{doc_id}.md")