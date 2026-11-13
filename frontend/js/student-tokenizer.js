// Transformers.js 3.0.2 truncates on the right. Preserve newest WordPieces
// explicitly, matching Python's BERT tokenizer with truncation_side='left'.
export async function studentTokenize(tokenizer, text, Tensor, maxLength = tokenizer.model_max_length) {
  const encoded = await tokenizer(text, { add_special_tokens: false, return_tensor: false,
    padding: false, truncation: false });
  const content = encoded.input_ids.slice(-(maxLength - 2));
  const cls = tokenizer.model.tokens_to_ids.get('[CLS]');
  if (cls === undefined || tokenizer.sep_token_id === undefined) throw new Error('Expected a BERT tokenizer');
  const input_ids = [cls, ...content, tokenizer.sep_token_id];
  const attention_mask = input_ids.map(() => 1);
  while (input_ids.length < maxLength) { input_ids.push(tokenizer.pad_token_id); attention_mask.push(0); }
  const token_type_ids = input_ids.map(() => 0);
  return Object.fromEntries(Object.entries({ input_ids, attention_mask, token_type_ids }).map(
    ([key, values]) => [key, new Tensor('int64', BigInt64Array.from(values, BigInt), [1, maxLength])]));
}
