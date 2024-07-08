import { Anthropic } from '@anthropic-ai/sdk';
import dotenv from 'dotenv';
dotenv.config();

const anthropic = new Anthropic({
  apiKey: process.env.CLAUDE_KEY,
});

export default anthropic;
