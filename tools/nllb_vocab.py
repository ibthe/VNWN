"""Готовит словарь токенизатора NLLB для приложения.

Использование: python tools/nllb_vocab.py <каталог с tokenizer.json и sentencepiece.bpe.model> <куда писать> <префикс>
Пишет <префикс>-vocab.tsv («оценка<TAB>токен», номер строки = id токена в HF) и <префикс>-added.tsv
(«id<TAB>токен» для служебных токенов и кодов языков). Если SentencePiece-модель BPE, в added.tsv
добавляется строка «-1<TAB>#bpe».
"""
import json, sys
from pathlib import Path
from sentencepiece import SentencePieceProcessor

src, out, prefix = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
t = json.load(open(src / 'tokenizer.json', encoding='utf-8'))
sp = SentencePieceProcessor(model_file=str(src / 'sentencepiece.bpe.model'))


def varint(b, i):
    r = 0; sh = 0
    while True:
        c = b[i]; i += 1
        r |= (c & 0x7f) << sh; sh += 7
        if not c & 0x80:
            return r, i


def fields(b):
    i = 0
    while i < len(b):
        tag, i = varint(b, i); f, w = tag >> 3, tag & 7
        if w == 0:
            v, i = varint(b, i); yield f, v
        elif w == 1: i += 8
        elif w == 5: i += 4
        elif w == 2:
            n, i = varint(b, i); yield f, b[i:i + n]; i += n
        else:
            raise ValueError('неизвестный wire type')


# model_type лежит в trainer_spec (поле 2 модели), номер поля 3: 1 = Unigram, 2 = BPE
mt = 1
for f, v in fields(open(src / 'sentencepiece.bpe.model', 'rb').read()):
    if f == 2 and isinstance(v, (bytes, bytearray)):
        for f2, v2 in fields(v):
            if f2 == 3 and isinstance(v2, int):
                mt = v2
        break
print('tokenizer.json:', t['model'].get('type'), '| spm model_type:', mt, '| токенов spm:', sp.get_piece_size())


def clean(x):
    return x.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')


# id в HF = id в spm + 1 (сдвиг fairseq); первые четыре: <s> <pad> </s> <unk>
rows = [('<s>', 0.0), ('<pad>', 0.0), ('</s>', 0.0), ('<unk>', 0.0)]
for i in range(3, sp.get_piece_size()):
    rows.append((clean(sp.id_to_piece(i)), float(sp.get_score(i))))

vocab = t['model']['vocab']
if isinstance(vocab, dict):
    bad = [(k, v) for k, v in vocab.items() if 4 <= v < len(rows) and rows[v][0] != clean(k)]
    print('расхождений нумерации:', len(bad), bad[:5])
    assert len(bad) < len(rows) * 0.001, 'нумерация spm и tokenizer.json не совпадает'

with open(out / f'{prefix}-vocab.tsv', 'w', encoding='utf-8', newline='\n') as f:
    for piece, score in rows:
        f.write(f"{score}\t{piece}\n")
with open(out / f'{prefix}-added.tsv', 'w', encoding='utf-8', newline='\n') as f:
    if mt == 2:
        f.write("-1\t#bpe\n")
    for a in t['added_tokens']:
        f.write(f"{a['id']}\t{clean(a['content'])}\n")
added = {a['content']: a['id'] for a in t['added_tokens']}
for k in ('</s>', '<unk>', '<pad>', 'rus_Cyrl', 'eng_Latn', 'kat_Geor'):
    assert k in added, f"нет токена {k}"
assert len(rows) >= 256000, 'словарь слишком маленький'
print('токенов в словаре:', len(rows), '| rus_Cyrl =', added['rus_Cyrl'], '| eng_Latn =', added['eng_Latn'], '| kat_Geor =', added['kat_Geor'])
