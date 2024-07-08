import OpenAi from './config/open-ai.js';
import Anthropic from '@anthropic-ai/sdk';
import express from 'express';
import bodyParser from 'body-parser';
import colors from 'colors';
import Tesseract from 'tesseract.js';
import { chromium } from 'playwright';
import path from 'path';
import { FileObjectsPage } from 'openai/resources/files.js';
import screenshot from 'screenshot-desktop'; // Install with `npm install screenshot-desktop`

import fs from 'fs/promises'; // Use fs.promises for async file operations
import anthropic from './config/claude.js';
import { Messages } from '@anthropic-ai/sdk/resources/messages.js';

const app = express();
const port = 3000;

//server
app.use(bodyParser.json());

const chatHistory = []; // Store conversation history

app.use(express.static('public'));

const __dirname = path.dirname(new URL(import.meta.url).pathname); // Resolve __dirname for ES Modules

app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

// Serve the CSS file explicitly
app.get('/index.css', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.css'));
});

app.get('/script.js', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'script.js'), { 'Content-Type': 'application/javascript' });
});

app.get('/search', async (req, res) => {
  // Your search logic here
  // ...
  // Send the search results as JSON
  res.json(searchResults);
});

// Scrape
async function performWebScraping(link) {
  const browser = await chromium.launch();
  const page = await browser.newPage();

  await page.goto(link);
  await page.screenshot({ path: 'screenshot.png', fullPage: true });

  await browser.close();
}

async function performOCR() {
  const { data: { text } } = await Tesseract.recognize('screenshot.png', 'eng', { logger: (info) => console.log('scraping....') });
  return text;
}

app.post('/scrape', async (req, res) => {
  const link = req.body.link;
  await performWebScraping(link);

  const ocrText = await performOCR();
  res.json({ ocrText });
});

app.post('/seescreen', async (req, res) => {
  try {
    const screenshotPath = path.join(__dirname, 'macos_screenshot.png');
    await screenshot({ filename: screenshotPath });

    const { data: { text } } = await Tesseract.recognize(screenshotPath, 'eng', { logger: (info) => console.log(info) });
    await fs.unlink(screenshotPath); // Delete the image after processing

    console.log('OCR Text:', text); // Log OCR text for debugging

    res.json({ ocrText: text });
  } catch (error) {
    console.error('Error capturing and processing screenshot:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

app.post('/ask', async (req, res) => {
  const userInput = req.body.query;
  let systemcontent = req.body.systemtxt;

  const maxLength = 1000;
  systemcontent = systemcontent.substring(0, maxLength);

  const scrapetext = req.body.ocrText;
  console.log('Request Body:', req.body);
  console.log('input', userInput);

  const searchQuery = req.body.searchQuery;

  try {
    // Construct messages by iterating over the history
    const messages = chatHistory.map(([role, content]) => ({
      role: role,
      content: content,
    }));

    // messages.push({ role: 'user', content: userInput });
    messages.push({
       "role": "user", "content": "Hi," + userInput + systemcontent || "Hello, Claude" 
      }
    );
    // messages.push({
    //   role: "user",
    //   content: searchQuery || "Hi",
    // });
    // messages.push({
    //   role: "assistant",
    //   content: systemcontent || "",
    // });

    // if (messages.length > maxLength) {
    //   messages.splice(0, messages.length - maxLength);
    // }

    // Make the API call to Anthropic
    const completion = await anthropic.messages.create({
      model: 'claude-3-5-sonnet-20240620',
      messages: messages,
      //[{ "role": "user", "content": "Hi," + userInput + systemcontent || "Hello, Claude" }
      //],
      max_tokens: 500,
      temperature: 0.9,
    });

  



    // Get completion text/content
    const completionText = completion.content[0].text;
    //console.log('API Response:', completionText);
    console.log(colors.green('Bot: ') + completionText);

    res.json({ botResponse: completionText }); // Send a JSON response

    
    // Update history with user input and assistant response
    chatHistory.push(['user', userInput]);
    chatHistory.push(['assistant', completionText]);
    //chatHistory.push(['system', systemcontent]);

    systemcontent = "";
    console.log("systemcontent 0")

  } catch (error) {
    console.error(colors.red(error));
    res.status(500).json({ error: 'Internal Server Error' }); // Send a JSON error response
  }
});

app.listen(port, () => {
  console.log(`Server is running on http://localhost:${port}`);
});
