import OpenAi from './config/open-ai.js';
import express from 'express';
import bodyParser from 'body-parser';
import colors from 'colors';
import Tesseract from 'tesseract.js';
import { chromium } from 'playwright';
import path from 'path';


const app = express();
const port = 3000;


//server
app.use(bodyParser.json());

const chatHistory = []; // Store conversation history

app.use(express.static('public'));

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


//scrape



async function performWebScraping(link) {
  const browser = await chromium.launch();
  const page = await browser.newPage();

  await page.goto(link);
  await page.screenshot({ path: 'screenshot.png', fullPage: true });
 
  await browser.close();
}

async function performOCR() {
  const { data: { text } } = await Tesseract.recognize('screenshot.png', 'eng', { logger: (info) => console.log(info) });
  return text;
}

app.use(express.json());

app.post('/scrape', async (req, res) => {
  const link = req.body.link;
  await performWebScraping(link);

  const ocrText = await performOCR();
  res.json({ ocrText });
});


//chat

app.post('/ask', async (req, res) => {
    const userInput = req.body.query;
    let systemcontent = String(req.body.systemtxt);
    
    let maxLength = 2000;

    // Update systemcontent to the substring result
    systemcontent = systemcontent.substring(0, maxLength);
    

    const scrapetext = req.body.ocrText;
    console.log('Request Body:', req.body);
    //const jorge = 'jorge;'
    //const SEARCH_KEYWORD = 'Search';
    //console.log(systemcontent);
    console.log("input", userInput);
   
    const searchQuery = req.body.searchQuery;
    //console.log(searchQuery); 
    try {
      
      
      
      
      //const usercontent = String(req.body.query.usertxt);
      //const systemanswercontent = String(req.body.systemtxtansw);
      
      // Construct messages by iterating over the history
      const messages = chatHistory.map(([role, content]) => ({
        role: role,
        content: content,
        
      }));
    

     
      messages.push({ role: 'user', content: userInput });

      messages.push({
        role: "system",
        content: `${systemcontent}` || "You are a super cool and helpfull AI assistant, you give answers in a funny manner, but you are also very intelligent and efficient. Use earlier chat history for extra information.", 
        
      },{
        role: "user",
        content: `${searchQuery}` || "",
        
      },{
        role: "system",
        content: "" || `Answer about ${systemcontent}.`,
        
      });
      
      // Make the API call to OpenAI
      const completion = await OpenAi.chat.completions.create({
        model: 'gpt-3.5-turbo',
        messages: messages,
        max_tokens: 1024,
        temperature: 0,
      });
      
      
      // Get completion text/content
      const completionText = completion.choices[0].message.content;
      //console.log(completionText);

      if (userInput.toLowerCase() === 'exit') {
        console.log(colors.green('Bot: ') + completionText);
        res.json({ botResponse: completionText }); // Send a JSON response
        return;
      }
  
      //console.log(colors.green('Bot: ') + completionText);
      res.json({ botResponse: completionText }); // Send a JSON response
  
      // Update history with user input and assistant response
      chatHistory.push(['user', userInput]);
      chatHistory.push(['system', completionText]);
      chatHistory.push(['system', systemcontent]);

    } catch (error) {
      console.error(colors.red(error));
      res.status(500).json({ error: 'Internal Server Error' }); // Send a JSON error response
    }
   
});

app.listen(port, () => {
  console.log(`Server is running on http://localhost:${port}`);
});





//-------Search--------





