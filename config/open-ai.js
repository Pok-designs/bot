import OpenAI from 'openai';
import dotenv from 'dotenv';
dotenv.config();


const openai = new OpenAI({
  apiKey: process.env.OPENAI_API_KEY, 
});

const systemcontent = "You are a helpful assistant.";
const usercontent = "What can you tell me about guitars?"
const systemanswercontent = "Guitars are stringed instruments"


export default openai;
